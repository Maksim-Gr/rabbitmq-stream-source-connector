package com.github.maksimgr

import org.apache.kafka.connect.data.Schema
import org.apache.kafka.connect.source.SourceRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class RecordBufferTest {
    @Test
    fun `is full when the record limit is reached`() {
        val buffer = RecordBuffer(maxRecords = 2, maxBytes = Long.MAX_VALUE)
        assertTrue(buffer.offer(record("a"), 1, 0, TimeUnit.MILLISECONDS))
        assertTrue(buffer.offer(record("b"), 1, 0, TimeUnit.MILLISECONDS))

        assertFalse(buffer.offer(record("c"), 1, 10, TimeUnit.MILLISECONDS))
        assertEquals(2, buffer.size)
    }

    @Test
    fun `is full when the byte limit is reached`() {
        val buffer = RecordBuffer(maxRecords = 100, maxBytes = 10)
        assertTrue(buffer.offer(record("a"), 6, 0, TimeUnit.MILLISECONDS))
        assertTrue(buffer.offer(record("b"), 4, 0, TimeUnit.MILLISECONDS))

        assertFalse(buffer.offer(record("c"), 1, 10, TimeUnit.MILLISECONDS))
        assertEquals(10, buffer.sizeBytes)
    }

    @Test
    fun `accepts a record larger than the byte limit when empty`() {
        val buffer = RecordBuffer(maxRecords = 100, maxBytes = 10)
        assertTrue(buffer.offer(record("huge"), 1_000, 0, TimeUnit.MILLISECONDS))

        assertFalse(buffer.offer(record("next"), 1, 10, TimeUnit.MILLISECONDS), "oversized record fills the buffer")
        assertEquals("huge", buffer.poll(0, TimeUnit.MILLISECONDS)?.value())
        assertTrue(buffer.offer(record("next"), 1, 0, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `poll and drainTo release bytes in FIFO order`() {
        val buffer = RecordBuffer(maxRecords = 100, maxBytes = 100)
        listOf("a", "b", "c").forEach { buffer.offer(record(it), 10, 0, TimeUnit.MILLISECONDS) }

        assertEquals("a", buffer.poll(0, TimeUnit.MILLISECONDS)?.value())
        assertEquals(20, buffer.sizeBytes)

        val drained = mutableListOf<SourceRecord>()
        assertEquals(1, buffer.drainTo(drained, 1))
        assertEquals(listOf("b"), drained.map { it.value() })
        assertEquals(10, buffer.sizeBytes)
    }

    @Test
    fun `poll returns null on timeout when empty`() {
        assertNull(RecordBuffer(1, 1).poll(10, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a blocked offer resumes when space is freed`() {
        val buffer = RecordBuffer(maxRecords = 100, maxBytes = 10)
        buffer.offer(record("a"), 10, 0, TimeUnit.MILLISECONDS)

        var accepted = false
        val producer = Thread { accepted = buffer.offer(record("b"), 5, 5, TimeUnit.SECONDS) }.apply { start() }
        producer.join(200)
        assertTrue(producer.isAlive, "offer should wait while the buffer is full")

        buffer.poll(0, TimeUnit.MILLISECONDS)
        producer.join(2_000)
        assertTrue(accepted)
        assertEquals(5, buffer.sizeBytes)
    }

    @Test
    fun `clear empties the buffer and resets the byte count`() {
        val buffer = RecordBuffer(maxRecords = 100, maxBytes = 100)
        buffer.offer(record("a"), 40, 0, TimeUnit.MILLISECONDS)
        buffer.clear()

        assertTrue(buffer.isEmpty())
        assertEquals(0, buffer.sizeBytes)
    }

    @Test
    fun `toString reports depth against both limits`() {
        val buffer = RecordBuffer(maxRecords = 10, maxBytes = 100)
        buffer.offer(record("a"), 42, 0, TimeUnit.MILLISECONDS)

        assertEquals("1 / 10 records, 42 / 100 bytes", buffer.toString())
    }

    @Test
    fun `offer throws when the thread is interrupted`() {
        val buffer = RecordBuffer(1, 1)
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedException::class.java) { buffer.offer(record("a"), 1, 0, TimeUnit.MILLISECONDS) }
        } finally {
            Thread.interrupted()
        }
    }

    private fun record(value: String) =
        SourceRecord(mapOf("queue" to "q"), mapOf("offset" to 0L), "topic", null, null, null, Schema.STRING_SCHEMA, value)
}
