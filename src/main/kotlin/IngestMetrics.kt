package com.github.maksimgr

import org.apache.kafka.common.MetricName
import org.apache.kafka.common.metrics.Gauge
import org.apache.kafka.common.metrics.PluginMetrics
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Updates and reads share a lock so a scrape cannot observe a partial start/stop transition. */
internal class ActivityTimer(
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var startedAt: Long? = null
    private var elapsedNanos = 0L

    val running: Boolean get() = synchronized(this) { startedAt != null }
    val seconds: Double
        get() =
            synchronized(this) {
                val elapsed = elapsedNanos + (startedAt?.let { nanoTime() - it } ?: 0L)
                elapsed.toDouble() / TimeUnit.SECONDS.toNanos(1)
            }

    @Synchronized
    fun start(): Boolean {
        if (startedAt != null) return false
        startedAt = nanoTime()
        return true
    }

    @Synchronized
    fun stop() {
        startedAt?.let { elapsedNanos += nanoTime() - it }
        startedAt = null
    }
}

/** Values describe delivery into the buffer, not Kafka acknowledgement or committed offsets. */
internal class StreamMetrics {
    val received = AtomicLong()
    val buffered = AtomicLong()
    val skipped = AtomicLong()
    val gaps = AtomicLong()
    val missingOffsets = AtomicLong()
    val rewinds = AtomicLong()
    val recoveries = AtomicLong()
    val lastBufferedOffset = AtomicLong(-1)
    val lastReceivedTimeMillis = AtomicLong(-1)
    val blocked = ActivityTimer()
    private val recovery = ActivityTimer()

    val recovering: Boolean get() = recovery.running
    val blockedSeconds: Double get() = blocked.seconds
    val recoverySeconds: Double get() = recovery.seconds

    fun recovering() {
        if (recovery.start()) recoveries.incrementAndGet()
    }

    fun recovered() {
        recovery.stop()
    }
}

/** Registers numeric gauges in Connect's plugin metrics registry, with connector/task tags supplied by Connect. */
internal class IngestMetrics(
    private val config: RabbitSourceConfig,
    private val buffer: RecordBuffer,
    streams: Set<String>,
) : AutoCloseable {
    val streams: Map<String, StreamMetrics> = streams.associateWith { StreamMetrics() }
    private var registry: PluginMetrics? = null
    private val names = mutableListOf<MetricName>()

    fun register(registry: PluginMetrics) {
        check(this.registry == null) { "Metrics already registered" }
        this.registry = registry
        gauge("buffer-records", "Records currently in the task buffer") { buffer.size }
        gauge("buffer-bytes", "Message body bytes currently in the task buffer") { buffer.sizeBytes }
        gauge("buffer-record-capacity", "Maximum buffered records") { config.bufferMaxRecords }
        gauge("buffer-byte-capacity", "Target maximum buffered message body bytes") { config.bufferMaxBytes }
        streams.forEach { (stream, values) -> registerStream(stream, values) }
    }

    private fun registerStream(
        stream: String,
        values: StreamMetrics,
    ) {
        val counters =
            mapOf(
                "received-total" to values.received,
                "buffered-total" to values.buffered,
                "skipped-total" to values.skipped,
                "gaps-total" to values.gaps,
                "missing-offsets-total" to values.missingOffsets,
                "rewinds-total" to values.rewinds,
                "recoveries-total" to values.recoveries,
                "last-buffered-offset" to values.lastBufferedOffset,
                "last-received-time-ms" to values.lastReceivedTimeMillis,
            )
        counters.forEach { (name, counter) -> gauge(name, "Stream $name since task start; -1 means unobserved", stream) { counter.get() } }
        gauge("recovering", "One while the stream consumer is recovering, otherwise zero", stream) { if (values.recovering) 1 else 0 }
        gauge("recovery-seconds-total", "Time spent recovering, including the current recovery", stream) { values.recoverySeconds }
        gauge("blocked-seconds-total", "Time blocked on a full buffer, including the current wait", stream) { values.blockedSeconds }
    }

    private fun gauge(
        name: String,
        description: String,
        stream: String? = null,
        value: () -> Number,
    ) {
        val registry = checkNotNull(registry)
        val tags = linkedMapOf("component" to "rabbitmq-stream")
        stream?.let { tags["stream"] = it }
        val metricName = registry.metricName(name, description, tags)
        registry.addMetric(metricName, Gauge<Number> { _, _ -> value() })
        names += metricName
    }

    override fun close() {
        names.forEach { registry?.removeMetric(it) }
        names.clear()
        registry = null
    }
}
