package com.github.maksimgr

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.assertDoesNotThrow
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RabbitSourceConnectorTest {
    private lateinit var connector: RabbitSourceConnector

    private val config =
        mapOf(
            "kafka.topic" to "your_kafka_topic",
            "rabbitmq.queue" to "test_queue",
            "rabbitmq.host" to "localhost",
            "rabbitmq.port" to "5672",
            "rabbitmq.username" to "guest",
            "rabbitmq.password" to "guest",
            "rabbitmq.virtual.host" to "/",
            "rabbitmq.requested.frame.max" to "0",
            "rabbitmq.handshake.timeout.ms" to "10000",
            "rabbitmq.requested.heartbeat.seconds" to "60",
            "rabbitmq.offset" to "first",
        )

    @BeforeEach
    fun setUp() {
        connector = RabbitSourceConnector()
    }

    @org.junit.jupiter.api.Test
    @DisplayName("Connector should return correct version")
    fun testVersion() {
        val version = connector.version()
        assertEquals("1.0.0", version)
    }

    @org.junit.jupiter.api.Test
    fun testConfig() {
        val configDef = connector.config()
        assertNotNull(configDef, "ConfigDef should not be null")
        assertFalse(configDef.names().isEmpty(), "ConfigDef should have names")
        assertDoesNotThrow { connector.start(config.toMutableMap()) }
    }

    @org.junit.jupiter.api.Test
    fun testStart() {
        val props = config.toMutableMap()
        assertDoesNotThrow { connector.start(props) }
    }

    @org.junit.jupiter.api.Test
    fun testTaskClass() {
        val taskClass = connector.taskClass()
        assertEquals(RabbitSourceTask::class.java, taskClass)
    }

    @org.junit.jupiter.api.Test
    fun testTaskConfig() {
        connector.start(config.toMutableMap())
        val taskConfigs = connector.taskConfigs(2)
        assertEquals(1, taskConfigs.size)
    }

    @org.junit.jupiter.api.Test
    fun testStop() {
        assertDoesNotThrow { connector.stop() }
    }

    @org.junit.jupiter.api.Test
    fun `taskConfigs should create correct number of tasks`() {
        connector.start(config.toMutableMap())
        val taskConfigs = connector.taskConfigs(3)

        // only 1 queue => only 1 task
        assertEquals(1, taskConfigs.size)
        assertEquals("test_queue", taskConfigs[0]["rabbitmq.queue"])
    }

    @org.junit.jupiter.api.Test
    fun `taskConfigs should distribute multiple queues`() {
        val multiConfig = config.toMutableMap()
        multiConfig["rabbitmq.queue"] = "test_queue,test_queue_2,test_queue_3"
        connector.start(multiConfig)
        val taskConfigs = connector.taskConfigs(3)
        assertEquals(3, taskConfigs.size)
        val queues = taskConfigs.flatMap { it["rabbitmq.queue"]!!.split(",") }
        assertTrue { queues.containsAll(listOf("test_queue", "test_queue_2", "test_queue_3")) }
    }
}
