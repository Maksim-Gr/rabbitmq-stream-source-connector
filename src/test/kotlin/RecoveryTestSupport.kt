package com.github.maksimgr

import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.testcontainers.containers.Network
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.shaded.com.fasterxml.jackson.databind.JsonNode
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.concurrent.TimeUnit

internal fun awaitRecovery(
    description: String,
    timeoutSeconds: Long = 120,
    condition: () -> Boolean,
) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
    var lastFailure: Throwable? = null
    while (System.nanoTime() < deadline) {
        if (runCatching(condition).onFailure { lastFailure = it }.getOrDefault(false)) return
        Thread.sleep(200)
    }
    fail<Unit>("Timed out waiting for $description", lastFailure)
}

internal class RecoveryRabbitCluster(
    network: Network,
) : AutoCloseable {
    val nodes: List<RabbitMQContainer> =
        (1..3).map { index ->
            val name = "rabbit$index"
            RabbitMQContainer("rabbitmq:3.13-management")
                .withNetwork(network)
                .withNetworkAliases(name)
                .withCreateContainerCmdModifier { it.withHostName(name) }
                .withExposedPorts(5672, 15672, 5552)
                .withEnv("RABBITMQ_NODENAME", "rabbit@$name")
                .withEnv("RABBITMQ_ERLANG_COOKIE", "recovery-integration-cluster")
                .withAdminUser(USER)
                .withAdminPassword(PASSWORD)
                .withEnv("RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS", "+S 2:2 +A 4 -rabbitmq_stream advertised_host $name")
                .withCommand("bash", "-c", "rabbitmq-plugins enable --offline rabbitmq_stream && rabbitmq-server")
                .withStartupTimeout(Duration.ofMinutes(2))
        }

    fun start() {
        nodes.forEachIndexed { index, node ->
            node.start()
            if (index > 0) {
                command(index, "rabbitmqctl", "stop_app")
                command(index, "rabbitmqctl", "reset")
                command(index, "rabbitmqctl", "join_cluster", "rabbit@rabbit1")
                command(index, "rabbitmqctl", "start_app")
            }
        }
        command(0, "rabbitmqctl", "await_online_nodes", "3")
    }

    fun command(
        node: Int,
        vararg args: String,
    ) {
        val result = nodes[node].execInContainer(*args)
        assertEquals(0, result.exitCode, "${args.toList()}: ${result.stdout} ${result.stderr}")
    }

    fun declare(stream: String) {
        connection(0).use { connection ->
            connection.createChannel().use { channel ->
                channel.queueDeclare(
                    stream,
                    true,
                    false,
                    false,
                    mapOf("x-queue-type" to "stream", "x-initial-cluster-size" to 3, "x-queue-leader-locator" to "client-local"),
                )
            }
        }
    }

    fun publish(
        streams: List<String>,
        range: IntRange,
        node: Int = 0,
        afterPublish: (Int) -> Unit = {},
    ) {
        connection(node).use { connection ->
            connection.createChannel().use { channel ->
                channel.confirmSelect()
                range.forEach { sequence ->
                    publishSequence(channel, streams, sequence)
                    afterPublish(sequence)
                }
            }
        }
    }

    private fun publishSequence(
        channel: Channel,
        streams: List<String>,
        sequence: Int,
    ) {
        streams.forEach { stream -> channel.basicPublish("", stream, null, "$stream:$sequence".toByteArray()) }
        channel.waitForConfirmsOrDie(10_000)
    }

    fun queueInfo(stream: String): JsonNode {
        val node = nodes[1]
        val uri = URI("http://${node.host}:${node.getMappedPort(15672)}/api/queues/%2F/$stream")
        val credentials = Base64.getEncoder().encodeToString("$USER:$PASSWORD".toByteArray())
        val request = HttpRequest.newBuilder(uri).header("Authorization", "Basic $credentials").timeout(Duration.ofSeconds(10)).build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode(), response.body())
        return ObjectMapper().readTree(response.body())
    }

    private fun connection(node: Int): Connection =
        ConnectionFactory()
            .apply {
                host = nodes[node].host
                port = nodes[node].amqpPort
                username = USER
                password = PASSWORD
            }.newConnection()

    override fun close() {
        nodes.asReversed().forEach { it.stop() }
        assertTrue(nodes.none { it.isRunning })
    }

    companion object {
        const val USER = "connector"
        const val PASSWORD = "integration-secret"
        const val BOOTSTRAP_SERVERS = "rabbit1:5552,rabbit2:5552,rabbit3:5552"
    }
}
