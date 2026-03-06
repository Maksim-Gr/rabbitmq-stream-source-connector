package com.github.maksimgr

import com.rabbitmq.stream.Environment
import com.rabbitmq.stream.Producer
import org.apache.kafka.connect.source.SourceTaskContext
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.mockito.Mockito.mock
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
        task.initialize(mock(SourceTaskContext::class.java))
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

        TimeUnit.SECONDS.sleep(5)

        val records = task.poll()

        assertFalse(records.isEmpty(), "Records should not be empty")
        val record = records.first()

        val value = record.value()
        assertTrue(value is String, "Record value should be a String")

        val payload = value as String
        assertEquals(testMessage, payload)
    }

    private fun createStream() {
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
            .stream("test_queue")
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
