package com.github.maksimgr

import com.github.dockerjava.api.model.ExposedPort
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.shaded.com.fasterxml.jackson.databind.JsonNode
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper
import org.testcontainers.utility.MountableFile
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

internal class RecoveryConnectCluster(
    network: Network,
) : AutoCloseable {
    val kafka =
        KafkaContainer(IMAGE).withNetwork(network).withListener("kafka:19092")
            .withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx512m")
    val workers: List<GenericContainer<*>> =
        (1..2).map { index ->
            GenericContainer(IMAGE)
                .withNetwork(network)
                .withNetworkAliases("worker-$index")
                .withEnv("KAFKA_HEAP_OPTS", "-Xms128m -Xmx512m")
                .withExposedPorts(PORT)
                .withCopyFileToContainer(
                    MountableFile.forHostPath(File(checkNotNull(System.getProperty("connector.plugin.dir"))).absolutePath),
                    "/opt/plugins",
                ).withCopyToContainer(Transferable.of(properties(index)), "/opt/connect-worker.properties")
                .withCommand("/opt/kafka/bin/connect-distributed.sh", "/opt/connect-worker.properties")
                .waitingFor(Wait.forHttp("/connectors").forPort(PORT).withStartupTimeout(Duration.ofMinutes(2)))
        }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private val mapper = ObjectMapper()

    fun start() {
        kafka.start()
        workers.forEach { it.start() }
    }

    fun create(
        name: String,
        streams: List<String>,
        bootstrap: String = RecoveryRabbitCluster.BOOTSTRAP_SERVERS,
    ) {
        val config =
            mapOf(
                "connector.class" to RabbitSourceConnector::class.java.name,
                "tasks.max" to "2",
                "rabbitmq.bootstrap.servers" to bootstrap,
                // A broken legacy address makes endpoint-precedence regressions fail this suite.
                "rabbitmq.host" to "unreachable.invalid",
                "rabbitmq.username" to RecoveryRabbitCluster.USER,
                "rabbitmq.password" to RecoveryRabbitCluster.PASSWORD,
                "rabbitmq.queue" to streams.joinToString(","),
                "rabbitmq.gap.handling" to "fail",
                "rabbitmq.queue.buffer.size" to "2",
                "rabbitmq.poll.max.batch.size" to "2",
                "rabbitmq.recovery.backoff.seconds" to "1",
                "rabbitmq.requested.heartbeat.seconds" to "2",
                "kafka.topic" to name,
            )
        request("/connectors", "POST", mapper.writeValueAsString(mapOf("name" to name, "config" to config)))
        awaitRecovery("$name tasks running") { assignments(name).size == streams.size.coerceAtMost(2) }
    }

    fun request(
        path: String,
        method: String = "GET",
        body: String? = null,
    ): JsonNode {
        val worker = workers.first { it.isRunning }
        val uri = workerUri(worker, path)
        val request =
            HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        assertTrue(response.statusCode() in 200..299, "$method $path: ${response.statusCode()} ${response.body()}")
        return mapper.readTree(response.body().ifBlank { "null" })
    }

    fun assignments(name: String): Set<String> {
        val status = request("/connectors/$name/status")
        assertEquals("RUNNING", status.path("connector").path("state").asText(), status.toString())
        val tasks = status.path("tasks").toList()
        assertTrue(tasks.all { it.path("state").asText() == "RUNNING" }, status.toString())
        return tasks.map { it.path("worker_id").asText() }.toSet()
    }

    fun awaitCommitted(
        name: String,
        streams: List<String>,
        offset: Long,
    ) {
        awaitRecovery("$name real committed offsets >= $offset for every stream") {
            val offsets = request("/connectors/$name/offsets").path("offsets").toList()
            streams.all { stream ->
                offsets.any {
                    it.path(
                        "partition",
                    ).path("queue").asText() == stream && it.path("offset").path("offset").asLong(-1) >= offset
                }
            }
        }
    }

    fun kill(worker: GenericContainer<*>) {
        worker.dockerClient.killContainerCmd(worker.containerId).withSignal("SIGKILL").exec()
    }

    fun restart(worker: GenericContainer<*>) {
        if (!worker.isRunning) worker.dockerClient.startContainerCmd(worker.containerId).exec()
        awaitRecovery("restarted Connect worker REST API") {
            val uri = workerUri(worker, "/connectors")
            http.send(
                HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).build(),
                HttpResponse.BodyHandlers.discarding(),
            ).statusCode() == 200
        }
    }

    fun consumer(topic: String): KafkaConsumer<String, String> =
        KafkaConsumer(
            mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG to "recovery-${UUID.randomUUID()}",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
            ),
            StringDeserializer(),
            StringDeserializer(),
        ).apply { subscribe(listOf(topic)) }

    private fun workerUri(
        worker: GenericContainer<*>,
        path: String,
    ): URI {
        // Docker may allocate a different host port after a raw restart; Testcontainers caches the original mapping.
        val port = worker.currentContainerInfo.networkSettings.ports.bindings.getValue(ExposedPort(PORT)).first().hostPortSpec
        return URI("http://${worker.host}:$port$path")
    }

    override fun close() {
        workers.asReversed().forEach { it.stop() }
        kafka.stop()
    }

    companion object {
        const val IMAGE = "apache/kafka:4.4.0"
        const val PORT = 8083

        private fun properties(index: Int): String =
            """
            bootstrap.servers=kafka:19092
            listeners=http://0.0.0.0:$PORT
            rest.advertised.host.name=worker-$index
            group.id=recovery-it
            config.storage.topic=recovery-configs
            offset.storage.topic=recovery-offsets
            status.storage.topic=recovery-status
            config.storage.replication.factor=1
            offset.storage.replication.factor=1
            status.storage.replication.factor=1
            offset.flush.interval.ms=1000
            scheduled.rebalance.max.delay.ms=1000
            session.timeout.ms=6000
            heartbeat.interval.ms=1000
            key.converter=org.apache.kafka.connect.storage.StringConverter
            value.converter=org.apache.kafka.connect.storage.StringConverter
            plugin.path=/opt/plugins
            plugin.discovery=service_load
            """.trimIndent()
    }
}
