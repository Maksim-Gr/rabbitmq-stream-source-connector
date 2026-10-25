package com.github.maksimgr

import com.rabbitmq.stream.Environment
import com.rabbitmq.stream.Message
import com.rabbitmq.stream.OffsetSpecification
import com.rabbitmq.stream.Producer
import com.rabbitmq.stream.Properties
import org.apache.kafka.connect.errors.ConnectException
import org.apache.kafka.connect.source.SourceRecord
import org.apache.kafka.connect.source.SourceTaskContext
import org.apache.kafka.connect.storage.OffsetStorageReader
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.testcontainers.containers.RabbitMQContainer
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RabbitSourceConnectorIntegrationTest {
    private lateinit var rabbitmq: RabbitMQContainer
    private lateinit var task: RabbitSourceTask

    private lateinit var host: String
    private var port: Int = 5552
    private var amqpPort: Int = 5672

    private val config = mutableMapOf<String, String>()

    @BeforeAll
    fun setup() {
        rabbitmq =
            RabbitMQContainer("rabbitmq:3.11-management")
                .withExposedPorts(5672, 15672, 5552)
                .withEnv("RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS", "-rabbitmq_stream advertised_host localhost")
                .withCommand("bash", "-c", "rabbitmq-plugins enable --offline rabbitmq_stream && rabbitmq-server")

        rabbitmq.start()

        host = rabbitmq.host
        port = rabbitmq.getMappedPort(5552)
        amqpPort = rabbitmq.getMappedPort(5672)

        config.putAll(
            mapOf(
                "kafka.topic" to "test_topic",
                "rabbitmq.queue" to "test_queue",
                "rabbitmq.host" to host,
                "rabbitmq.port" to port.toString(),
                "rabbitmq.username" to "guest",
                "rabbitmq.password" to "guest",
                "rabbitmq.virtual.host" to "/",
                "rabbitmq.handshake.timeout.ms" to "30000",
                "rabbitmq.requested.heartbeat.seconds" to "60",
                "rabbitmq.offset" to "first",
            ),
        )

        createStream()
    }

    @AfterAll
    fun tearDown() {
        rabbitmq.stop()
    }

    @BeforeEach
    fun startTask() {
        task = RabbitSourceTask()
        // The real Connect runtime always supplies an OffsetStorageReader; stub one
        // here (returning no committed offset) so the task starts from the configured
        // offset instead of NPEing on a null reader.
        val context = mock(SourceTaskContext::class.java)
        `when`(context.offsetStorageReader()).thenReturn(mock(OffsetStorageReader::class.java))
        task.initialize(context)
        task.start(config)
    }

    @AfterEach
    fun stopTask() {
        task.stop()
    }

    @Test
    fun `should consume message from RabbitMQ`() {
        val testMessage = "Hello World!"
        sendMessageToRabbitMQ("test_queue", testMessage)

        val deadline = System.currentTimeMillis() + 10_000
        var records = emptyList<SourceRecord>()
        while (System.currentTimeMillis() < deadline && records.isEmpty()) {
            records = task.poll()
            if (records.isEmpty()) Thread.sleep(100)
        }

        assertFalse(records.isEmpty(), "Should have received records within 10s")
        val record: SourceRecord = records.first()

        val value: Any? = record.value()
        assertTrue(value is String, "Record value should be a String")

        val payload = value as String
        assertEquals(testMessage, payload)
    }

    @Test
    fun `should resume from committed offset after restart`() {
        val streamName = "resume_queue"
        createStream(streamName)
        (1..5).forEach { sendMessageToRabbitMQ(streamName, "message-$it") }

        // First run: no committed offset, so the task starts from the configured
        // offset ('first') and consumes everything published so far.
        val firstTask = startResumeTask(streamName, committedOffset = null)
        val firstRun = pollUntil(firstTask, 5)
        assertEquals((1..5).map { "message-$it" }, firstRun.map { it.value() as String })
        val lastCommitted = firstRun.last().sourceOffset()["offset"] as Long
        firstTask.stop()

        (6..7).forEach { sendMessageToRabbitMQ(streamName, "message-$it") }

        // Restart with the committed offset as the Connect runtime would supply it after
        // an offset flush. The JSON converter round-trips small numbers as Integer, so
        // pass an Int to exercise the same coercion the real runtime triggers.
        val restartedTask = startResumeTask(streamName, committedOffset = lastCommitted.toInt())
        val secondRun = pollUntil(restartedTask, 2)
        restartedTask.stop()

        assertEquals(
            listOf("message-6", "message-7"),
            secondRun.map { it.value() as String },
            "Restarted task must resume after the committed offset: no replay, no gap",
        )
    }

    @Test
    fun `should ignore an offset stored on the broker by a pre-0_3_0 connector`() {
        val streamName = "stale_offset_queue"
        createStream(streamName)
        (1..10).forEach { sendMessageToRabbitMQ(streamName, "message-$it") }
        storeOffsetOnBroker(streamName, "kafka-connector-$streamName", offset = 4)

        val freshTask = startResumeTask(streamName, committedOffset = null)
        val fromConfigured = pollUntil(freshTask, 10)
        freshTask.stop()
        assertEquals(
            (1..10).map { "message-$it" },
            fromConfigured.map { it.value() as String },
            "With no committed offset the task must start from rabbitmq.offset, not the broker-stored offset",
        )

        val resumedTask = startResumeTask(streamName, committedOffset = 7L)
        val fromCommitted = pollUntil(resumedTask, 2)
        resumedTask.stop()
        assertEquals(
            listOf("message-9", "message-10"),
            fromCommitted.map { it.value() as String },
            "The task must resume after the Kafka-committed offset, not the broker-stored offset",
        )
    }

    @Test
    fun `should fail the task on a structured amqp-value body when error handling is fail`() {
        val streamName = "structured_fail_queue"
        createStream(streamName)
        sendBodiesToRabbitMQ(streamName, listOf(mapOf("orderId" to 7), "after".toByteArray()))

        val failTask = startResumeTask(streamName, committedOffset = null)
        val records = mutableListOf<SourceRecord>()
        val deadline = System.currentTimeMillis() + 15_000
        var error: ConnectException? = null
        while (System.currentTimeMillis() < deadline && error == null) {
            try {
                records.addAll(failTask.poll())
            } catch (e: ConnectException) {
                error = e
            }
        }
        failTask.stop()

        assertNotNull(error, "The task must fail on a map body instead of writing it to Kafka")
        assertTrue(error!!.cause?.message.orEmpty().contains("structured AMQP value"))
        assertTrue(records.isEmpty(), "No record at or after the failed offset may reach Kafka")
    }

    @Test
    fun `should skip structured amqp-value bodies and keep scalar ones when error handling is skip`() {
        val streamName = "structured_skip_queue"
        createStream(streamName)
        sendBodiesToRabbitMQ(
            streamName,
            listOf(mapOf("orderId" to 7), listOf("a", "b"), "from amqp 1.0", "ok".toByteArray()),
        )

        val skipTask = startResumeTask(streamName, committedOffset = null, "rabbitmq.error.handling" to "skip")
        val records = pollUntil(skipTask, 2)
        skipTask.stop()

        assertEquals(listOf("from amqp 1.0", "ok"), records.map { it.value() as String })
        assertEquals(listOf(2L, 3L), records.map { it.sourceOffset()["offset"] })
    }

    /** The stream client encodes a non-binary body as an AMQP 1.0 `amqp-value` section. */
    private class AmqpValueMessage(
        private val value: Any,
    ) : Message {
        override fun hasPublishingId() = false

        override fun getPublishingId() = 0L

        override fun getBodyAsBinary(): ByteArray = error("not binary")

        override fun getBody(): Any = value

        override fun getProperties(): Properties? = null

        override fun getApplicationProperties(): MutableMap<String, Any>? = null

        override fun getMessageAnnotations(): MutableMap<String, Any>? = null
    }

    private fun sendBodiesToRabbitMQ(
        streamName: String,
        bodies: List<Any>,
    ) {
        val environment =
            Environment
                .builder()
                .host(host)
                .port(port)
                .username("guest")
                .password("guest")
                .build()

        val producer: Producer =
            environment
                .producerBuilder()
                .stream(streamName)
                .build()

        val latch = java.util.concurrent.CountDownLatch(bodies.size)
        bodies.forEach { body ->
            val msg = if (body is ByteArray) producer.messageBuilder().addData(body).build() else AmqpValueMessage(body)
            producer.send(msg) { confirmationStatus ->
                if (confirmationStatus.isConfirmed) {
                    latch.countDown()
                }
            }
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS), "all messages should be confirmed")

        producer.close()
        environment.close()
    }

    private fun storeOffsetOnBroker(
        streamName: String,
        consumerName: String,
        offset: Long,
    ) {
        val environment =
            Environment
                .builder()
                .host(host)
                .port(port)
                .username("guest")
                .password("guest")
                .build()

        val consumer =
            environment
                .consumerBuilder()
                .stream(streamName)
                .name(consumerName)
                .offset(OffsetSpecification.first())
                .manualTrackingStrategy()
                .builder()
                .messageHandler { ctx, _ -> if (ctx.offset() == offset) ctx.storeOffset() }
                .build()

        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && runCatching { consumer.storedOffset() }.getOrNull() != offset) {
            Thread.sleep(100)
        }
        assertEquals(offset, consumer.storedOffset(), "offset should be stored on the broker before the test runs")

        consumer.close()
        environment.close()
    }

    private fun startResumeTask(
        streamName: String,
        committedOffset: Any?,
        vararg overrides: Pair<String, String>,
    ): RabbitSourceTask {
        val reader = mock(OffsetStorageReader::class.java)
        if (committedOffset != null) {
            `when`(reader.offset(mapOf("queue" to streamName))).thenReturn(mapOf("offset" to committedOffset))
        }
        val context = mock(SourceTaskContext::class.java)
        `when`(context.offsetStorageReader()).thenReturn(reader)

        val resumeTask = RabbitSourceTask()
        resumeTask.initialize(context)
        val taskConfig = config.toMutableMap().apply { put("rabbitmq.queue", streamName) } + overrides
        resumeTask.start(taskConfig.toMutableMap())
        return resumeTask
    }

    private fun pollUntil(
        pollingTask: RabbitSourceTask,
        expectedCount: Int,
        timeoutMs: Long = 15_000,
    ): List<SourceRecord> {
        val records = mutableListOf<SourceRecord>()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && records.size < expectedCount) {
            records.addAll(pollingTask.poll())
        }
        return records
    }

    @Test
    fun `should carry AMQP properties into record timestamp and headers when enabled`() {
        val streamName = "props_queue"
        createStream(streamName)

        val creationTimeMillis = System.currentTimeMillis()
        sendMessageWithPropertiesToRabbitMQ(streamName, "with-props", creationTimeMillis)

        val reader = mock(OffsetStorageReader::class.java)
        val context = mock(SourceTaskContext::class.java)
        `when`(context.offsetStorageReader()).thenReturn(reader)

        val propsTask = RabbitSourceTask()
        propsTask.initialize(context)
        propsTask.start(
            config.toMutableMap().apply {
                put("rabbitmq.queue", streamName)
                put("rabbitmq.headers.enabled", "true")
                put("rabbitmq.headers.amqp.enabled", "true")
            },
        )

        val records = pollUntil(propsTask, 1)
        propsTask.stop()

        assertFalse(records.isEmpty(), "Should have received the message")
        val record = records.first()

        assertEquals(creationTimeMillis, record.timestamp())
        assertEquals("corr-123", record.headers().lastWithName("amqp.correlationId").value())
        assertEquals("text/plain", record.headers().lastWithName("amqp.contentType").value())
        assertEquals(creationTimeMillis, record.headers().lastWithName("amqp.creationTime").value())
        assertEquals("bar", record.headers().lastWithName("foo").value())
    }

    private fun sendMessageWithPropertiesToRabbitMQ(
        streamName: String,
        body: String,
        creationTimeMillis: Long,
    ) {
        val environment =
            Environment
                .builder()
                .host(host)
                .port(port)
                .username("guest")
                .password("guest")
                .build()

        val producer: Producer =
            environment
                .producerBuilder()
                .stream(streamName)
                .build()

        val latch = java.util.concurrent.CountDownLatch(1)

        val msg =
            producer
                .messageBuilder()
                .properties()
                .correlationId("corr-123")
                .contentType("text/plain")
                .creationTime(creationTimeMillis)
                .messageBuilder()
                .applicationProperties()
                .entry("foo", "bar")
                .messageBuilder()
                .addData(body.toByteArray())
                .build()

        producer.send(msg) { confirmationStatus ->
            if (confirmationStatus.isConfirmed) {
                latch.countDown()
            }
        }

        latch.await(5, TimeUnit.SECONDS)

        producer.close()
        environment.close()
    }

    private fun createStream(streamName: String = "test_queue") {
        val environment =
            Environment
                .builder()
                .host(host)
                .port(port)
                .username("guest")
                .password("guest")
                .build()

        environment
            .streamCreator()
            .stream(streamName)
            .create()

        environment.close()
    }

    private fun sendMessageToRabbitMQ(
        streamName: String,
        message: String,
    ) {
        val environment =
            Environment
                .builder()
                .host(host)
                .port(port)
                .username("guest")
                .password("guest")
                .build()

        val producer: Producer =
            environment
                .producerBuilder()
                .stream(streamName)
                .build()

        val latch = java.util.concurrent.CountDownLatch(1)

        val msg = producer.messageBuilder().addData(message.toByteArray()).build()

        producer.send(msg) { confirmationStatus ->
            if (confirmationStatus.isConfirmed) {
                latch.countDown()
            }
        }

        latch.await(5, TimeUnit.SECONDS)

        producer.close()
        environment.close()
    }
}
