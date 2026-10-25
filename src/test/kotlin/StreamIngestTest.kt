package com.github.maksimgr

import com.rabbitmq.stream.Message
import com.rabbitmq.stream.Resource
import org.apache.kafka.connect.errors.ConnectException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.concurrent.atomic.AtomicBoolean

class StreamIngestTest {
    private val ingests = mutableListOf<StreamIngest>()

    @AfterEach
    fun tearDown() {
        ingests.forEach { it.close() }
    }

    @Test
    fun `poll returns an empty list when nothing was delivered`() {
        assertTrue(ingest().poll().isEmpty())
    }

    @Test
    fun `delivered binary body becomes a string record with the stream partition and offset`() {
        val ingest = ingest()
        ingest.deliver(STREAM, 5, message(binary = "hello".toByteArray()))

        val record = ingest.poll().single()
        assertEquals("hello", record.value())
        assertEquals("test-topic", record.topic())
        assertEquals(mapOf("queue" to STREAM), record.sourcePartition())
        assertEquals(mapOf("offset" to 5L), record.sourceOffset())
    }

    @Test
    fun `bytes format emits the body as raw bytes`() {
        val ingest = ingest("rabbitmq.message.format" to "bytes")
        ingest.deliver(STREAM, 0, message(binary = byteArrayOf(1, 2, 3)))

        assertArrayEquals(byteArrayOf(1, 2, 3), ingest.poll().single().value() as ByteArray)
    }

    @Test
    fun `poll returns at most pollMaxBatchSize records`() {
        val ingest = ingest("rabbitmq.poll.max.batch.size" to "3")
        repeat(10) { i -> ingest.deliver(STREAM, i.toLong(), message(binary = "msg-$i".toByteArray())) }

        assertEquals(3, ingest.poll().size, "poll should be capped at pollMaxBatchSize")
    }

    @Test
    fun `poll drains all delivered records`() {
        val ingest = ingest()
        repeat(5) { i -> ingest.deliver(STREAM, i.toLong(), message(binary = "msg-$i".toByteArray())) }

        assertEquals((0 until 5).map { "msg-$it" }, ingest.poll().map { it.value() })
    }

    @Test
    fun `non-binary amqp-value scalar body is converted to its string form`() {
        val ingest = ingest()
        ingest.deliver(STREAM, 0, message(binary = null, native = "from amqp 1.0", binaryThrows = true))
        ingest.deliver(STREAM, 1, message(binary = null, native = 42, binaryThrows = true))

        assertEquals(listOf("from amqp 1.0", "42"), ingest.poll().map { it.value() })
    }

    @Test
    fun `empty body emits a null value with an optional schema`() {
        val ingest = ingest()
        ingest.deliver(STREAM, 0, message(binary = null))

        val record = ingest.poll().single()
        assertNull(record.value())
        assertTrue(record.valueSchema().isOptional)
    }

    @Test
    fun `structured amqp-value or amqp-sequence body fails the task when error handling is fail`() {
        listOf<Any>(mapOf("orderId" to 7), listOf("a", "b"), arrayOf("a")).forEach { native ->
            val ingest = ingest()
            ingest.deliver(STREAM, 3, message(binary = null, native = native, binaryThrows = true))

            val error = assertThrows(ConnectException::class.java, { ingest.poll() }, "expected $native to be rejected")
            assertTrue(error.cause?.message.orEmpty().contains("structured AMQP value"))
        }
    }

    @Test
    fun `structured body is skipped when error handling is skip`() {
        val ingest = ingest("rabbitmq.error.handling" to "skip")
        ingest.deliver(STREAM, 3, message(binary = null, native = mapOf("orderId" to 7), binaryThrows = true))
        ingest.deliver(STREAM, 4, message(binary = "ok".toByteArray()))

        assertEquals(listOf("ok"), ingest.poll().map { it.value() })
    }

    @Test
    fun `failing message fails the task when error handling is fail`() {
        val ingest = ingest()
        ingest.deliver(STREAM, 7, poisonMessage())

        assertThrows(ConnectException::class.java) { ingest.poll() }
    }

    @Test
    fun `failing message is skipped when error handling is skip`() {
        val ingest = ingest("rabbitmq.error.handling" to "skip")
        ingest.deliver(STREAM, 7, poisonMessage())
        ingest.deliver(STREAM, 8, message(binary = "ok".toByteArray()))

        assertEquals(listOf("ok"), ingest.poll().map { it.value() })
    }

    @Test
    fun `no message after a failing one is buffered when error handling is fail`() {
        val ingest = ingest()
        ingest.deliver(STREAM, 7, poisonMessage())
        ingest.deliver(STREAM, 8, message(binary = "after-failure".toByteArray()))

        assertTrue(ingest.bufferStatus.startsWith("0 /"), "a record past the failed offset must not reach Kafka")
        assertThrows(ConnectException::class.java) { ingest.poll() }
    }

    @Test
    fun `interrupting delivery while running fails the task`() {
        val ingest = ingest()
        Thread.currentThread().interrupt()
        try {
            ingest.deliver(STREAM, 3, message(binary = "interrupted".toByteArray()))
        } finally {
            Thread.interrupted()
        }

        val error = assertThrows(ConnectException::class.java) { ingest.poll() }
        assertTrue(error.cause?.message.orEmpty().contains("at offset 3"))
    }

    @Test
    fun `interrupting delivery after close does not fail the task`() {
        val ingest = ingest()
        ingest.close()
        Thread.currentThread().interrupt()
        try {
            ingest.deliver(STREAM, 3, message(binary = "interrupted".toByteArray()))
        } finally {
            Thread.interrupted()
        }

        assertTrue(ingest.poll().isEmpty())
    }

    @Test
    fun `delivery continues after an offset rewind`() {
        val ingest = ingest(resumeOffset = 10)
        ingest.deliver(STREAM, 4, message(binary = "redelivered".toByteArray()))

        assertEquals("redelivered", ingest.poll().single().value())
    }

    @Test
    fun `delivery continues after an offset gap`() {
        val ingest = ingest(resumeOffset = 10)
        ingest.deliver(STREAM, 15, message(binary = "after-gap".toByteArray()))

        assertEquals("after-gap", ingest.poll().single().value())
    }

    @Test
    fun `delivery for a stream this task does not consume is rejected`() {
        assertThrows(IllegalStateException::class.java) {
            ingest().deliver("other", 0, message(binary = "x".toByteArray()))
        }
    }

    @Test
    fun `binary application properties are copied as byte headers`() {
        val ingest = ingest("rabbitmq.headers.enabled" to "true")
        val msg = message(binary = "body".toByteArray())
        `when`(msg.applicationProperties).thenReturn(mapOf("raw" to byteArrayOf(1, 2), "text" to "value"))
        ingest.deliver(STREAM, 0, msg)

        val headers = ingest.poll().single().headers()
        assertArrayEquals(byteArrayOf(1, 2), headers.lastWithName("raw").value() as ByteArray)
        assertEquals("value", headers.lastWithName("text").value())
    }

    @Test
    fun `close releases a delivery blocked on a full buffer`() {
        val ingest = ingest("rabbitmq.queue.buffer.size" to "1")
        ingest.deliver(STREAM, 0, message(binary = "fills-buffer".toByteArray()))

        val finished = AtomicBoolean(false)
        val blocked =
            Thread {
                ingest.deliver(STREAM, 1, message(binary = "blocked".toByteArray()))
                finished.set(true)
            }.apply { start() }

        blocked.join(300)
        assertFalse(finished.get(), "delivery should apply backpressure while the buffer is full")

        ingest.close()
        blocked.join(2_000)
        assertTrue(finished.get(), "delivery should return once ingest is closed")
    }

    @Test
    fun `delivery applies backpressure when the buffer byte limit is reached`() {
        val ingest = ingest("rabbitmq.queue.buffer.max.bytes" to "10")
        ingest.deliver(STREAM, 0, message(binary = "12345678".toByteArray()))

        val finished = AtomicBoolean(false)
        val blocked =
            Thread {
                ingest.deliver(STREAM, 1, message(binary = "abcd".toByteArray()))
                finished.set(true)
            }.apply { start() }

        blocked.join(300)
        assertFalse(finished.get(), "8 + 4 bytes exceeds the 10-byte limit, so delivery should wait")

        val records = ingest.poll().toMutableList()
        blocked.join(2_000)
        assertTrue(finished.get(), "delivery should resume once poll() frees space")
        records += ingest.poll()
        assertEquals(listOf("12345678", "abcd"), records.map { it.value() })
    }

    @Test
    fun `consumer closing unexpectedly fails the task`() {
        val ingest = ingest()
        ingest.stateChanged(STREAM, Resource.State.OPEN, Resource.State.CLOSED)

        val error = assertThrows(ConnectException::class.java) { ingest.poll() }
        assertTrue(error.cause?.message.orEmpty().contains("closed unexpectedly"))
    }

    @Test
    fun `consumer closing after close does not fail the task`() {
        val ingest = ingest()
        ingest.close()
        ingest.stateChanged(STREAM, Resource.State.OPEN, Resource.State.CLOSED)

        assertTrue(ingest.poll().isEmpty())
    }

    private fun ingest(
        vararg overrides: Pair<String, String>,
        resumeOffset: Long? = null,
    ): StreamIngest {
        val props =
            mapOf(
                "kafka.topic" to "test-topic",
                "rabbitmq.queue" to STREAM,
                "rabbitmq.username" to "guest",
                "rabbitmq.password" to "guest",
            ) + overrides
        return StreamIngest(RabbitSourceConfig(props), mapOf(STREAM to resumeOffset)).also { ingests += it }
    }

    private fun message(
        binary: ByteArray?,
        native: Any? = binary,
        binaryThrows: Boolean = false,
    ): Message =
        mock(Message::class.java).also {
            if (binaryThrows) {
                `when`(it.bodyAsBinary).thenThrow(IllegalStateException("Body cannot be returned as array of bytes"))
            } else {
                `when`(it.bodyAsBinary).thenReturn(binary)
            }
            `when`(it.body).thenReturn(native)
        }

    /** A message whose body cannot be read at all, simulating a record that fails conversion. */
    private fun poisonMessage(): Message =
        mock(Message::class.java).also {
            `when`(it.bodyAsBinary).thenThrow(IllegalArgumentException("corrupt body"))
        }

    private companion object {
        const val STREAM = "test"
    }
}
