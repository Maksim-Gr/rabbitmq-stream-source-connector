package com.github.maksimgr

import org.apache.kafka.clients.consumer.KafkaConsumer
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.extension.TestWatcher
import org.testcontainers.containers.Network
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the packaged plugin, real worker offset storage, and broker-advertised cluster addresses. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConnectorRecoveryIntegrationTest {
    private val network = Network.newNetwork()
    private val rabbit = RecoveryRabbitCluster(network)
    private val connect = RecoveryConnectCluster(network)

    @JvmField
    @RegisterExtension
    val failureLogs: TestWatcher =
        object : TestWatcher {
            override fun testFailed(
                context: ExtensionContext,
                cause: Throwable?,
            ) {
                (connect.workers + rabbit.nodes).forEachIndexed { index, container ->
                    println("Recovery container $index logs:\n${container.logs.takeLast(30_000)}")
                }
            }
        }

    @BeforeAll
    fun setup() {
        rabbit.start()
        connect.start()
    }

    @AfterEach
    fun removeConnectors() {
        connect.request("/connectors").forEach { name -> connect.request("/connectors/${name.asText()}", "DELETE") }
    }

    @AfterAll
    fun tearDown() {
        try {
            connect.close()
        } finally {
            try {
                rabbit.close()
            } finally {
                network.close()
            }
        }
    }

    @Test
    fun `hard crashes of both workers recover real committed offsets without missing messages`() {
        val name = "crash-${UUID.randomUUID()}"
        val streams = listOf("$name-a", "$name-b")
        streams.forEach(rabbit::declare)
        rabbit.publish(streams, 0..49)
        connect.create(name, streams)
        connect.consumer(name).use { consumer ->
            val received = mutableListOf<String>()
            awaitMessages(consumer, received, expected(streams, 0..49))
            connect.awaitCommitted(name, streams, 49)
            val beforeFailure = received.size
            try {
                publishAcrossFailure(streams, 50..249) { connect.workers.forEach(connect::kill) }
            } finally {
                connect.workers.forEach(connect::restart)
            }
            awaitMessages(consumer, received, expected(streams, 0..249))
            val committedBeforeCrash = expected(streams, 0..49)
            assertTrue(received.drop(beforeFailure).none { it in committedBeforeCrash }, "Restart must honor real committed offsets")
            connect.awaitCommitted(name, streams, 249)
        }
    }

    @Test
    fun `tasks move to the surviving worker and rebalance when the killed worker rejoins`() {
        val name = "rebalance-${UUID.randomUUID()}"
        val streams = listOf("$name-a", "$name-b")
        streams.forEach(rabbit::declare)
        connect.create(name, streams)
        connect.consumer(name).use { consumer ->
            val received = mutableListOf<String>()
            rabbit.publish(streams, 0..49)
            awaitMessages(consumer, received, expected(streams, 0..49))
            connect.awaitCommitted(name, streams, 49)
            val victim = connect.workers.first()
            try {
                publishAcrossFailure(streams, 50..149) { connect.kill(victim) }
                awaitRecovery("both tasks reassigned to the surviving worker") {
                    val tasks = connect.request("/connectors/$name/status").path("tasks").toList()
                    tasks.size == 2 && tasks.all { it.path("state").asText() == "RUNNING" } && connect.assignments(name).size == 1
                }
                awaitMessages(consumer, received, expected(streams, 0..149))
            } finally {
                connect.restart(victim)
            }
            awaitRecovery("tasks distributed over both workers again") { connect.assignments(name).size == 2 }
            rabbit.publish(streams, 150..199)
            awaitMessages(consumer, received, expected(streams, 0..199))
            connect.awaitCommitted(name, streams, 199)
        }
    }

    @Test
    fun `Kafka write outage recovers buffered and in-flight records without losing source messages`() {
        val name = "kafka-outage-${UUID.randomUUID()}"
        val streams = listOf(name)
        rabbit.declare(name)
        connect.create(name, streams)
        connect.consumer(name).use { consumer ->
            val received = mutableListOf<String>()
            rabbit.publish(streams, 0..19)
            awaitMessages(consumer, received, expected(streams, 0..19))
            connect.awaitCommitted(name, streams, 19)
            val docker = connect.kafka.dockerClient
            docker.pauseContainerCmd(connect.kafka.containerId).exec()
            try {
                rabbit.publish(streams, 20..219)
                // An established consumer cannot fetch the new records while the broker is paused.
                assertTrue(consumer.poll(Duration.ofSeconds(3)).isEmpty)
            } finally {
                docker.unpauseContainerCmd(connect.kafka.containerId).exec()
            }
            awaitMessages(consumer, received, expected(streams, 0..219))
            connect.awaitCommitted(name, streams, 219)
        }
    }

    @Test
    fun `leader failover and startup with an unavailable bootstrap node use surviving advertised addresses`() {
        val name = "leader-${UUID.randomUUID()}"
        rabbit.declare(name)
        awaitRecovery("stream replicated on three nodes with its leader on rabbit1") {
            val info = rabbit.queueInfo(name)
            info.path("leader").asText() == "rabbit@rabbit1" && info.path("online").size() == 3
        }
        rabbit.publish(listOf(name), 0..19)
        connect.create(name, listOf(name))
        connect.consumer(name).use { consumer ->
            val received = mutableListOf<String>()
            awaitMessages(consumer, received, expected(listOf(name), 0..19))
            connect.awaitCommitted(name, listOf(name), 19)
            rabbit.command(0, "rabbitmqctl", "stop_app")
            try {
                awaitRecovery("a surviving replica elected stream leader") {
                    val leader = rabbit.queueInfo(name).path("leader").asText()
                    leader in setOf("rabbit@rabbit2", "rabbit@rabbit3")
                }
                rabbit.publish(listOf(name), 20..39, node = 1)
                awaitMessages(consumer, received, expected(listOf(name), 0..39))
                connect.awaitCommitted(name, listOf(name), 39)
                // Start another connector while its first bootstrap endpoint is still down.
                val fresh = "$name-fresh"
                connect.create(fresh, listOf(name))
                connect.consumer(fresh).use { freshConsumer ->
                    awaitMessages(freshConsumer, mutableListOf(), expected(listOf(name), 0..39))
                }
            } finally {
                rabbit.command(0, "rabbitmqctl", "start_app")
                rabbit.command(0, "rabbitmqctl", "await_online_nodes", "3")
            }
        }
    }

    private fun publishAcrossFailure(
        streams: List<String>,
        range: IntRange,
        injectFailure: () -> Unit,
    ) {
        val publishing = CountDownLatch(1)
        val failureInjected = CountDownLatch(1)
        val producer =
            CompletableFuture.runAsync {
                rabbit.publish(streams, range) { sequence ->
                    if (sequence == range.first + 20) {
                        publishing.countDown()
                        assertTrue(failureInjected.await(60, TimeUnit.SECONDS), "Failure injection must finish")
                    }
                }
            }
        try {
            assertTrue(publishing.await(30, TimeUnit.SECONDS), "Publisher must have confirmed records before failure injection")
            injectFailure()
        } finally {
            failureInjected.countDown()
            producer.get(60, TimeUnit.SECONDS)
        }
    }

    private fun awaitMessages(
        consumer: KafkaConsumer<String, String>,
        received: MutableList<String>,
        expected: Set<String>,
    ) {
        awaitRecovery("all ${expected.size} confirmed messages reaching Kafka") {
            consumer.poll(Duration.ofMillis(500)).forEach { received += it.value() }
            received.toSet().containsAll(expected)
        }
        assertEquals(expected, received.toSet(), "Kafka must contain every confirmed source message and no unexpected values")
        println("Recovery verification: ${expected.size} unique messages, ${received.size - received.toSet().size} duplicates")
    }

    private fun expected(
        streams: List<String>,
        range: IntRange,
    ): Set<String> = streams.flatMap { stream -> range.map { "$stream:$it" } }.toSet()
}
