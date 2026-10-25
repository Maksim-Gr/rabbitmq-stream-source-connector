package com.github.maksimgr

import org.apache.kafka.connect.errors.ConnectException
import org.apache.kafka.connect.source.SourceTaskContext
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class RabbitSourceTaskTest {
    private lateinit var task: RabbitSourceTask

    @BeforeEach
    fun setUp() {
        task = RabbitSourceTask()
        task.initialize(mock(SourceTaskContext::class.java))
    }

    @Test
    fun testVersion() {
        val version = task.version()
        assertTrue(version.isNotBlank(), "Version should not be blank")
        assertNotEquals("unknown", version, "Version should be resolved from version.properties")
    }

    @Test
    fun `start with an invalid config fails with ConnectException`() {
        assertThrows(ConnectException::class.java) { task.start(mutableMapOf("rabbitmq.queue" to "test")) }
    }

    @Test
    fun `stop is safe before start`() {
        assertDoesNotThrow { task.stop() }
    }

    @Test
    fun `poll before start fails`() {
        assertThrows(IllegalStateException::class.java) { task.poll() }
    }
}
