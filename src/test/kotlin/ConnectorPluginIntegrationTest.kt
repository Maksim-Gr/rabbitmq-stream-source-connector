package com.github.maksimgr

import com.rabbitmq.client.ConnectionFactory
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.MountableFile
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Deploys the unpacked component archive (build/plugin) into a real Kafka Connect worker that only
 * discovers plugins through ServiceLoader manifests, then streams a message end-to-end. This checks
 * the published artifact itself rather than the classes on the test classpath.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConnectorPluginIntegrationTest {
    private val network = Network.newNetwork()

    private val kafka =
        KafkaContainer(KAFKA_IMAGE)
            .withNetwork(network)
            .withListener("kafka:19092")

    private val rabbitmq =
        RabbitMQContainer("rabbitmq:3.13-management")
            .withNetwork(network)
            .withNetworkAliases("rabbitmq")
            .withEnv("RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS", "-rabbitmq_stream advertised_host rabbitmq")
            .withCommand("bash", "-c", "rabbitmq-plugins enable --offline rabbitmq_stream && rabbitmq-server")

    private val connect =
        GenericContainer(KAFKA_IMAGE)
            .withNetwork(network)
            .withExposedPorts(CONNECT_PORT)
            .withCopyFileToContainer(MountableFile.forHostPath(pluginDir().absolutePath), "/opt/plugins")
            .withCopyToContainer(Transferable.of(WORKER_PROPERTIES), "/opt/connect-worker.properties")
            .withCommand("/opt/kafka/bin/connect-distributed.sh", "/opt/connect-worker.properties")
            .waitingFor(Wait.forHttp("/connectors").forPort(CONNECT_PORT).withStartupTimeout(Duration.ofMinutes(2)))

    private val http = HttpClient.newHttpClient()

    @BeforeAll
    fun setup() {
        kafka.start()
        rabbitmq.start()
        connect.dependsOn(kafka, rabbitmq).start()
    }

    @AfterAll
    fun tearDown() {
        connect.stop()
        rabbitmq.stop()
        kafka.stop()
        network.close()
    }

    @Test
    fun `worker discovers the connector through its ServiceLoader manifest`() {
        val plugins = get("/connector-plugins")
        assertTrue(plugins.contains(RabbitSourceConnector::class.java.name), "Connector not discovered: $plugins")
    }

    @Test
    fun `packaged connector streams a message from RabbitMQ into Kafka`() {
        declareStream(STREAM)
        publish(STREAM, "hello from the archive")

        post(
            "/connectors",
            """
            {
              "name": "rabbitmq-source",
              "config": {
                "connector.class": "${RabbitSourceConnector::class.java.name}",
                "tasks.max": "1",
                "rabbitmq.host": "rabbitmq",
                "rabbitmq.port": "5552",
                "rabbitmq.username": "guest",
                "rabbitmq.password": "guest",
                "rabbitmq.queue": "$STREAM",
                "rabbitmq.offset": "first",
                "kafka.topic": "$TOPIC"
              }
            }
            """.trimIndent(),
        )

        assertEquals(listOf("hello from the archive"), consume(TOPIC, 1))
    }

    private fun declareStream(name: String) {
        newAmqpConnection().use { connection ->
            connection.createChannel().queueDeclare(name, true, false, false, mapOf("x-queue-type" to "stream"))
        }
    }

    private fun publish(
        stream: String,
        body: String,
    ) {
        newAmqpConnection().use { connection ->
            connection.createChannel().basicPublish("", stream, null, body.toByteArray())
        }
    }

    private fun newAmqpConnection() =
        ConnectionFactory()
            .apply {
                host = rabbitmq.host
                port = rabbitmq.amqpPort
                username = rabbitmq.adminUsername
                password = rabbitmq.adminPassword
            }.newConnection()

    private fun consume(
        topic: String,
        expected: Int,
    ): List<String> {
        val props =
            mapOf<String, Any>(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG to "plugin-it-verifier",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            )
        KafkaConsumer(props, StringDeserializer(), StringDeserializer()).use { consumer ->
            consumer.subscribe(listOf(topic))
            val values = mutableListOf<String>()
            val deadline = System.currentTimeMillis() + 60_000
            while (values.size < expected && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach { values += it.value() }
            }
            return values
        }
    }

    private fun get(path: String): String = send(HttpRequest.newBuilder(connectUri(path)).GET().build())

    private fun post(
        path: String,
        json: String,
    ): String =
        send(
            HttpRequest
                .newBuilder(connectUri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(),
        )

    private fun send(request: HttpRequest): String {
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        assertTrue(response.statusCode() in 200..299, "${request.uri()} -> ${response.statusCode()}: ${response.body()}")
        return response.body()
    }

    private fun connectUri(path: String) = URI("http://${connect.host}:${connect.getMappedPort(CONNECT_PORT)}$path")

    private companion object {
        const val KAFKA_IMAGE = "apache/kafka:4.4.0"
        const val CONNECT_PORT = 8083
        const val STREAM = "plugin_it_stream"
        const val TOPIC = "plugin.it"

        val WORKER_PROPERTIES =
            """
            bootstrap.servers=kafka:19092
            listeners=http://0.0.0.0:$CONNECT_PORT
            group.id=plugin-it
            config.storage.topic=connect-configs
            offset.storage.topic=connect-offsets
            status.storage.topic=connect-status
            config.storage.replication.factor=1
            offset.storage.replication.factor=1
            status.storage.replication.factor=1
            key.converter=org.apache.kafka.connect.storage.StringConverter
            value.converter=org.apache.kafka.connect.storage.StringConverter
            plugin.path=/opt/plugins
            plugin.discovery=service_load
            """.trimIndent()

        fun pluginDir(): File {
            val path =
                requireNotNull(System.getProperty("connector.plugin.dir")) {
                    "connector.plugin.dir is not set; run this test through ./gradlew integrationTest"
                }
            return File(path)
        }
    }
}
