package com.github.maksimgr

import org.apache.kafka.common.config.ConfigException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class RabbitSourceConfigTest {
    private fun validProps(overrides: Map<String, String> = emptyMap()): MutableMap<String, String> {
        val props =
            mutableMapOf(
                "kafka.topic" to "test_topic",
                "rabbitmq.queue" to "test_queue",
                "rabbitmq.username" to "guest",
                "rabbitmq.password" to "guest",
            )
        props.putAll(overrides)
        return props
    }

    @Test
    @DisplayName("Valid minimal configuration is accepted")
    fun testValidMinimalConfig() {
        assertDoesNotThrow { RabbitSourceConfig(validProps()) }
    }

    @Test
    @DisplayName("Each missing required property is rejected")
    fun testMissingRequiredProperties() {
        val required = listOf("kafka.topic", "rabbitmq.queue", "rabbitmq.username", "rabbitmq.password")
        required.forEach { name ->
            val props = validProps()
            props.remove(name)
            assertThrows(ConfigException::class.java, { RabbitSourceConfig(props) }, "Expected failure when '$name' is missing")
        }
    }

    @Test
    @DisplayName("Empty kafka.topic is rejected")
    fun testEmptyTopicRejected() {
        assertThrows(ConfigException::class.java) {
            RabbitSourceConfig(validProps(mapOf("kafka.topic" to "   ")))
        }
    }

    @Test
    @DisplayName("Port outside 1-65535 is rejected, valid port accepted")
    fun testPortRange() {
        assertThrows(ConfigException::class.java) {
            RabbitSourceConfig(validProps(mapOf("rabbitmq.port" to "0")))
        }
        assertThrows(ConfigException::class.java) {
            RabbitSourceConfig(validProps(mapOf("rabbitmq.port" to "65536")))
        }
        assertDoesNotThrow { RabbitSourceConfig(validProps(mapOf("rabbitmq.port" to "5552"))) }
    }

    @Test
    @DisplayName("Offset accepts first/last/next and a valid timestamp, rejects anything else")
    fun testOffsetValidation() {
        listOf("first", "last", "next", "FIRST", "01.01.2024 12:00:00").forEach { offset ->
            assertDoesNotThrow({ RabbitSourceConfig(validProps(mapOf("rabbitmq.offset" to offset))) }, "Expected '$offset' to be valid")
        }
        listOf("earliest", "2024-01-01 12:00:00", "42abc").forEach { offset ->
            assertThrows(
                ConfigException::class.java,
                { RabbitSourceConfig(validProps(mapOf("rabbitmq.offset" to offset))) },
                "Expected '$offset' to be rejected",
            )
        }
    }

    @Test
    @DisplayName("Message format accepts string/bytes, rejects anything else")
    fun testMessageFormatValidation() {
        listOf("string", "bytes", "BYTES").forEach { format ->
            assertDoesNotThrow(
                { RabbitSourceConfig(validProps(mapOf("rabbitmq.message.format" to format))) },
                "Expected '$format' to be valid",
            )
        }
        assertThrows(ConfigException::class.java) {
            RabbitSourceConfig(validProps(mapOf("rabbitmq.message.format" to "json")))
        }
    }

    @Test
    @DisplayName("Buffer size, recovery backoff and poll batch size must be at least 1")
    fun testAtLeastOneRanges() {
        listOf("rabbitmq.queue.buffer.size", "rabbitmq.recovery.backoff.seconds", "rabbitmq.poll.max.batch.size").forEach { name ->
            assertThrows(
                ConfigException::class.java,
                { RabbitSourceConfig(validProps(mapOf(name to "0"))) },
                "Expected '$name' = 0 to be rejected",
            )
        }
    }

    @Test
    @DisplayName("Defaults are applied for optional settings")
    fun testDefaults() {
        val config = RabbitSourceConfig(validProps())
        assertEquals("localhost", config.getString("rabbitmq.host"))
        assertEquals(5552, config.getInt("rabbitmq.port"))
        assertEquals("/", config.getString("rabbitmq.virtual.host"))
        assertEquals("first", config.getString("rabbitmq.offset"))
        assertEquals("string", config.getString("rabbitmq.message.format"))
        assertEquals(false, config.getBoolean("rabbitmq.tls.enabled"))
        assertEquals(false, config.getBoolean("rabbitmq.headers.enabled"))
        assertEquals(false, config.getBoolean("rabbitmq.headers.amqp.enabled"))
        assertEquals("JKS", config.getString("rabbitmq.tls.truststore.type"))
        assertEquals("JKS", config.getString("rabbitmq.tls.keystore.type"))
        assertEquals(10000, config.getInt("rabbitmq.queue.buffer.size"))
        assertEquals(5, config.getInt("rabbitmq.recovery.backoff.seconds"))
        assertEquals(1000, config.getInt("rabbitmq.poll.max.batch.size"))
    }

    @Test
    @DisplayName("Truststore/keystore type accepts JKS/PKCS12, rejects anything else")
    fun testKeystoreTypeValidation() {
        listOf("rabbitmq.tls.truststore.type", "rabbitmq.tls.keystore.type").forEach { name ->
            listOf("JKS", "jks", "PKCS12", "pkcs12").forEach { type ->
                assertDoesNotThrow({ RabbitSourceConfig(validProps(mapOf(name to type))) }, "Expected '$type' to be valid for $name")
            }
            assertThrows(
                ConfigException::class.java,
                { RabbitSourceConfig(validProps(mapOf(name to "PEM"))) },
                "Expected 'PEM' to be rejected for $name",
            )
        }
    }

    @Test
    @DisplayName("Password properties are not exposed via toString")
    fun testPasswordRedaction() {
        val config = RabbitSourceConfig(validProps(mapOf("rabbitmq.password" to "s3cret")))
        assertEquals("[hidden]", config.getPassword("rabbitmq.password").toString())
        assertEquals("s3cret", config.getPassword("rabbitmq.password").value())
    }

    @Test
    @DisplayName("Multiple queues are parsed as a list")
    fun testQueueListParsing() {
        val config = RabbitSourceConfig(validProps(mapOf("rabbitmq.queue" to "orders,events")))
        assertEquals(listOf("orders", "events"), config.getList("rabbitmq.queue"))
    }
}
