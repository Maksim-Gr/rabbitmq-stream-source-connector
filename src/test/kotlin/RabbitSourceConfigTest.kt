package com.github.maksimgr

import org.apache.kafka.common.config.ConfigException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertNotNull

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
    fun `empty normalized stream lists are rejected by construction and REST validation`() {
        listOf("", "   ", ", ,").forEach { streams ->
            val props = validProps(mapOf("rabbitmq.queue" to streams))
            assertThrows(ConfigException::class.java) { RabbitSourceConfig(props) }
            val value = RabbitSourceConfig.CONFIG.validate(props).single { it.name() == "rabbitmq.queue" }
            assertTrue(value.errorMessages().isNotEmpty())
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
        listOf("earliest", "2024-01-01 12:00:00", "42abc", "31.02.2024 12:00:00", "29.02.2023 12:00:00").forEach { offset ->
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
    @DisplayName("Error handling accepts fail/skip, rejects anything else")
    fun testErrorHandlingValidation() {
        listOf("fail", "skip", "SKIP").forEach { handling ->
            assertDoesNotThrow(
                { RabbitSourceConfig(validProps(mapOf("rabbitmq.error.handling" to handling))) },
                "Expected '$handling' to be valid",
            )
        }
        assertThrows(ConfigException::class.java) {
            RabbitSourceConfig(validProps(mapOf("rabbitmq.error.handling" to "all")))
        }
    }

    @Test
    @DisplayName("Buffer limits, recovery backoff and poll batch size must be at least 1")
    fun testAtLeastOneRanges() {
        listOf(
            "rabbitmq.queue.buffer.size",
            "rabbitmq.queue.buffer.max.bytes",
            "rabbitmq.recovery.backoff.seconds",
            "rabbitmq.poll.max.batch.size",
        ).forEach { name ->
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
        assertEquals(64L * 1024 * 1024, config.getLong("rabbitmq.queue.buffer.max.bytes"))
        assertEquals(5, config.getInt("rabbitmq.recovery.backoff.seconds"))
        assertEquals(1000, config.getInt("rabbitmq.poll.max.batch.size"))
        assertEquals("fail", config.getString("rabbitmq.error.handling"))
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

    @Test
    @DisplayName("Stream names are trimmed, and empty entries and duplicates are dropped")
    fun testStreamsCleanup() {
        val config = RabbitSourceConfig(validProps(mapOf("rabbitmq.queue" to "orders, ,events,orders,")))
        assertEquals(listOf("orders", "events"), config.streams)
    }

    @Test
    @DisplayName("Choice values are exposed as enums, ignoring case and surrounding whitespace")
    fun testTypedChoices() {
        val config =
            RabbitSourceConfig(
                validProps(
                    mapOf(
                        "rabbitmq.message.format" to " BYTES ",
                        "rabbitmq.error.handling" to "Skip",
                    ),
                ),
            )
        assertEquals(MessageFormat.BYTES, config.messageFormat)
        assertEquals(ErrorHandling.SKIP, config.errorHandling)
    }

    @Test
    @DisplayName("Typed defaults match the documented defaults")
    fun testTypedDefaults() {
        val config = RabbitSourceConfig(validProps())
        assertEquals(MessageFormat.STRING, config.messageFormat)
        assertEquals(ErrorHandling.FAIL, config.errorHandling)
        assertEquals(10000, config.bufferMaxRecords)
        assertEquals(64L * 1024 * 1024, config.bufferMaxBytes)
        assertEquals(1000, config.pollMaxBatchSize)
        assertEquals(Duration.ofSeconds(60), config.requestedHeartbeat)
        assertEquals(Duration.ofSeconds(5), config.recoveryBackoff)
        assertEquals("first", config.configuredOffset)
        assertNull(config.messageKeySource)
        assertNull(config.tls)
    }

    @Test
    @DisplayName("TLS settings are null when disabled and omit stores without a path")
    fun testTlsSettings() {
        val config =
            RabbitSourceConfig(
                validProps(
                    mapOf(
                        "rabbitmq.tls.enabled" to "true",
                        "rabbitmq.tls.truststore.path" to "/tmp/trust.p12",
                        "rabbitmq.tls.truststore.password" to "s3cret",
                        "rabbitmq.tls.truststore.type" to "pkcs12",
                    ),
                ),
            )
        val tls = assertNotNull(config.tls)
        val truststore = assertNotNull(tls.truststore)
        assertEquals("/tmp/trust.p12", truststore.path)
        assertEquals(StoreType.PKCS12, truststore.type)
        assertEquals("s3cret", truststore.password.value())
        assertNull(tls.keystore)
    }

    @Test
    @DisplayName("forStreams replaces only the stream list")
    fun testForStreams() {
        val props = validProps()
        val taskProps = RabbitSourceConfig.forStreams(props, listOf("a", "b"))
        assertEquals(listOf("a", "b"), RabbitSourceConfig(taskProps).streams)
        assertEquals(props - "rabbitmq.queue", taskProps - "rabbitmq.queue")
    }
}
