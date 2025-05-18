import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.assertDoesNotThrow
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class RabbitSourceConnectorTest {
    private lateinit var connector: RabbitSourceConnector

    private val config =
        mapOf(
            "kafka.topic" to "your_kafka_topic",
            "rabbitmq.queue" to "queue_name_here",
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
        assertEquals(2, taskConfigs.size)
    }

    @org.junit.jupiter.api.Test
    fun testStop() {
        assertDoesNotThrow { connector.stop() }
    }
}
