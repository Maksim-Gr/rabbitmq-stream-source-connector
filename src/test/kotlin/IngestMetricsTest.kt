package com.github.maksimgr

import com.rabbitmq.stream.Message
import com.rabbitmq.stream.Resource
import org.apache.kafka.common.metrics.JmxReporter
import org.apache.kafka.common.metrics.KafkaMetricsContext
import org.apache.kafka.common.metrics.Metrics
import org.apache.kafka.common.metrics.internals.PluginMetricsImpl
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit
import javax.management.ObjectName

class IngestMetricsTest {
    private val config =
        RabbitSourceConfig(
            mapOf(
                "kafka.topic" to "metrics-test",
                "rabbitmq.queue" to "orders,events",
                "rabbitmq.username" to "guest",
                "rabbitmq.password" to "guest",
                "rabbitmq.error.handling" to "skip",
                "rabbitmq.queue.buffer.size" to "1",
            ),
        )

    @Test
    fun `registered gauges report buffering loss and recovery separately for each stream`() {
        Metrics().use { registry ->
            registry.addReporter(JmxReporter().apply { contextChange(KafkaMetricsContext("rabbitmq.stream.test")) })
            PluginMetricsImpl(registry, mapOf("connector" to "test", "task" to "0")).use { plugin ->
                StreamIngest(config, mapOf("orders" to 10L, "events" to null)).use { ingest ->
                    ingest.metrics.register(plugin)
                    assertEquals(-1L, value(registry, "last-buffered-offset", "orders").toLong())
                    ingest.deliver("orders", 15, message("ok"))
                    assertEquals(1, value(registry, "buffer-records").toInt())
                    assertEquals(2L, value(registry, "buffer-bytes").toLong())
                    assertEquals(15L, value(registry, "last-buffered-offset", "orders").toLong())
                    assertEquals(1L, value(registry, "gaps-total", "orders").toLong())
                    assertEquals(5L, value(registry, "missing-offsets-total", "orders").toLong())
                    val bean = ObjectName("rabbitmq.stream.test:type=plugins,connector=test,task=0,component=rabbitmq-stream,stream=orders")
                    assertEquals(1L, ManagementFactory.getPlatformMBeanServer().getAttribute(bean, "received-total"))
                    ingest.poll()
                    val poison = mock(Message::class.java)
                    `when`(poison.bodyAsBinary).thenThrow(IllegalArgumentException("corrupt"))
                    ingest.deliver("events", 0, poison)
                    assertEquals(1L, value(registry, "skipped-total", "events").toLong())
                    assertEquals(0L, value(registry, "skipped-total", "orders").toLong())
                    assertEquals(0L, value(registry, "buffered-total", "events").toLong())
                    assertTrue(value(registry, "last-received-time-ms", "events").toLong() > 0)
                    ingest.stateChanged("orders", Resource.State.OPEN, Resource.State.RECOVERING)
                    ingest.stateChanged("orders", Resource.State.RECOVERING, Resource.State.RECOVERING)
                    assertEquals(1, value(registry, "recovering", "orders").toInt())
                    assertEquals(1L, value(registry, "recoveries-total", "orders").toLong())
                    assertTrue(value(registry, "recovery-seconds-total", "orders").toDouble() > 0)
                    ingest.stateChanged("orders", Resource.State.RECOVERING, Resource.State.OPEN)
                    assertEquals(0, value(registry, "recovering", "orders").toInt())
                }
                assertTrue(registry.metrics().keys.none { it.group() == "plugins" })
            }
        }
    }

    @Test
    fun `blocked time is observable before a full buffer is drained`() {
        StreamIngest(config, mapOf("orders" to null)).use { ingest ->
            ingest.deliver("orders", 0, message("full"))
            val blocked = Thread { ingest.deliver("orders", 1, message("pending")) }
            blocked.start()
            try {
                val metrics = ingest.metrics.streams.getValue("orders")
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!metrics.blocked.running && System.nanoTime() < deadline) Thread.sleep(10)
                assertTrue(metrics.blockedSeconds > 0)
                ingest.poll()
                blocked.join(5_000)
                assertFalse(blocked.isAlive)
                assertFalse(metrics.blocked.running)
                assertTrue(metrics.blockedSeconds > 0)
                assertEquals(2L, metrics.buffered.get())
            } finally {
                blocked.interrupt()
                blocked.join(5_000)
            }
        }
    }

    @Test
    fun `activity duration remains cumulative across repeated starts stops and idle periods`() {
        var now = 0L
        val timer = ActivityTimer { now }
        assertEquals(0.0, timer.seconds)
        assertTrue(timer.start())
        now = TimeUnit.SECONDS.toNanos(2)
        assertFalse(timer.start(), "Repeated recovery notifications must not reset the start time")
        assertEquals(2.0, timer.seconds)
        timer.stop()
        timer.stop()
        now = TimeUnit.SECONDS.toNanos(5)
        assertEquals(2.0, timer.seconds, "Idle time must not count")
        timer.start()
        now = TimeUnit.SECONDS.toNanos(6)
        assertEquals(3.0, timer.seconds)
        timer.stop()
        assertEquals(3.0, timer.seconds)
    }

    @Test
    fun `task tags isolate metrics and stopping one task leaves the other registered`() {
        Metrics().use { registry ->
            val first = PluginMetricsImpl(registry, mapOf("connector" to "test", "task" to "0"))
            val second = PluginMetricsImpl(registry, mapOf("connector" to "test", "task" to "1"))
            val one = StreamIngest(config, mapOf("orders" to null))
            val two = StreamIngest(config, mapOf("orders" to null))
            try {
                one.metrics.register(first)
                two.metrics.register(second)
                one.close()
                val tasks = registry.metrics().keys.filter { it.group() == "plugins" }.map { it.tags()["task"] }.toSet()
                assertEquals(setOf("1"), tasks)
            } finally {
                one.close()
                two.close()
                first.close()
                second.close()
            }
        }
    }

    private fun value(
        registry: Metrics,
        name: String,
        stream: String? = null,
    ): Number =
        registry.metrics().entries.single { (metric) -> metric.name() == name && metric.tags()["stream"] == stream }
            .value.metricValue() as Number

    private fun message(body: String): Message = mock(Message::class.java).also { `when`(it.bodyAsBinary).thenReturn(body.toByteArray()) }
}
