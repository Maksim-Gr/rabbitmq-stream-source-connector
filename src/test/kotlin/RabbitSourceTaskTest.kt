package com.github.maksimgr

import org.apache.kafka.connect.data.Schema
import org.apache.kafka.connect.source.SourceRecord
import org.apache.kafka.connect.source.SourceTaskContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.lang.reflect.Field

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
    fun `poll returns empty list when queue is empty`() {
        val records = task.poll()
        assertTrue(records.isEmpty(), "Expected empty list when no messages enqueued")
    }

    @Test
    fun `poll returns records after they are enqueued`() {
        val queue = getMessageQueue(task)
        val record =
            SourceRecord(
                mapOf("queue" to "test"),
                mapOf("offset" to 0L),
                "test-topic",
                null,
                null,
                null,
                Schema.STRING_SCHEMA,
                "hello",
            )
        queue.put(record)

        val records = task.poll()
        assertFalse(records.isEmpty(), "Expected non-empty list after enqueuing a record")
        assertEquals("hello", records.first().value())
    }

    @Test
    fun `poll returns at most pollMaxBatchSize records`() {
        setPollMaxBatchSize(task, 3)
        val queue = getMessageQueue(task)
        repeat(10) { i ->
            queue.put(
                SourceRecord(
                    mapOf("queue" to "test"),
                    mapOf("offset" to i.toLong()),
                    "test-topic",
                    null,
                    null,
                    null,
                    Schema.STRING_SCHEMA,
                    "msg-$i",
                ),
            )
        }

        val records = task.poll()
        assertEquals(3, records.size, "poll should be capped at pollMaxBatchSize")
    }

    @Test
    fun `poll drains all enqueued records`() {
        val queue = getMessageQueue(task)
        repeat(5) { i ->
            queue.put(
                SourceRecord(
                    mapOf("queue" to "test"),
                    mapOf("offset" to i.toLong()),
                    "test-topic",
                    null,
                    null,
                    null,
                    Schema.STRING_SCHEMA,
                    "msg-$i",
                ),
            )
        }

        val records = task.poll()
        assertEquals(5, records.size)
    }

    @Test
    fun `poll throws when a message handler recorded a failure`() {
        setFailure(task, RuntimeException("boom"))
        val error =
            org.junit.jupiter.api.Assertions.assertThrows(
                org.apache.kafka.connect.errors.ConnectException::class.java,
            ) { task.poll() }
        assertEquals("boom", error.cause?.message)
    }

    private fun setFailure(
        task: RabbitSourceTask,
        value: Throwable,
    ) {
        val field: Field = RabbitSourceTask::class.java.getDeclaredField("failure")
        field.isAccessible = true
        field.set(task, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun getMessageQueue(task: RabbitSourceTask): java.util.concurrent.LinkedBlockingQueue<SourceRecord> {
        val field: Field = RabbitSourceTask::class.java.getDeclaredField("messageQueue")
        field.isAccessible = true
        return field.get(task) as java.util.concurrent.LinkedBlockingQueue<SourceRecord>
    }

    private fun setPollMaxBatchSize(
        task: RabbitSourceTask,
        value: Int,
    ) {
        val field: Field = RabbitSourceTask::class.java.getDeclaredField("pollMaxBatchSize")
        field.isAccessible = true
        field.setInt(task, value)
    }
}
