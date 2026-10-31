package com.github.maksimgr

import com.rabbitmq.stream.Message
import com.rabbitmq.stream.Properties
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
    fun `interrupted delivery can be replayed after recovery without failing the task`() {
        val ingest = ingest("rabbitmq.queue.buffer.size" to "1")
        ingest.deliver(STREAM, 0, message(binary = "buffered".toByteArray()))
        val blocked = Thread { ingest.deliver(STREAM, 1, message(binary = "pending".toByteArray())) }
        blocked.start()
        try {
            blocked.join(300)
            assertTrue(blocked.isAlive)
            blocked.interrupt()
            blocked.join(2_000)
            assertFalse(blocked.isAlive)
            assertEquals(1L, ingest.subscriptionOffset(STREAM))
            assertEquals("buffered", ingest.poll().single().value())
            ingest.stateChanged(STREAM, Resource.State.RECOVERING, Resource.State.OPEN)
            ingest.deliver(STREAM, 1, message(binary = "pending".toByteArray()))
            assertEquals("pending", ingest.poll().single().value())
        } finally {
            ingest.close()
            blocked.interrupt()
            blocked.join(2_000)
        }
    }

    @Test
    fun `recovery pins the first interrupted message and ignores later callbacks on that thread`() {
        val ingest = ingest()
        assertNull(ingest.subscriptionOffset(STREAM))
        val msg = message(binary = byteArrayOf(1))
        // Interrupt after the callback begins, as a disconnect does during delivery.
        `when`(msg.bodyAsBinary).thenAnswer {
            Thread.currentThread().interrupt()
            byteArrayOf(1)
        }
        try {
            ingest.deliver(STREAM, 42, msg)
            ingest.deliver(STREAM, 43, message(binary = byteArrayOf(2)))
        } finally {
            Thread.interrupted()
        }
        assertEquals(42L, ingest.subscriptionOffset(STREAM))
        assertTrue(ingest.poll().isEmpty())
        ingest.deliver(STREAM, 42, message(binary = byteArrayOf(1)))
        assertEquals(43L, ingest.subscriptionOffset(STREAM))
        assertEquals(mapOf("offset" to 42L), ingest.poll().single().sourceOffset())
    }

    @Test
    fun `recovery pins an offset even when the first callback is already interrupted`() {
        val ingest = ingest()
        Thread.currentThread().interrupt()
        try {
            ingest.deliver(STREAM, 42, message(binary = byteArrayOf(1)))
            ingest.deliver(STREAM, 43, message(binary = byteArrayOf(2)))
        } finally {
            Thread.interrupted()
        }
        assertEquals(42L, ingest.subscriptionOffset(STREAM))
        assertTrue(ingest.poll().isEmpty())
    }

    @Test
    fun `recovery advances past skipped messages and preserves independent stream positions`() {
        val config =
            RabbitSourceConfig(
                mapOf(
                    "kafka.topic" to "test-topic",
                    "rabbitmq.queue" to "one,two",
                    "rabbitmq.username" to "guest",
                    "rabbitmq.password" to "guest",
                    "rabbitmq.error.handling" to "skip",
                ),
            )
        val ingest = StreamIngest(config, mapOf("one" to 10L, "two" to 20L)).also { ingests += it }
        assertEquals(10L, ingest.subscriptionOffset("one"))
        ingest.deliver("one", 10, poisonMessage())
        assertEquals(11L, ingest.subscriptionOffset("one"))
        assertEquals(20L, ingest.subscriptionOffset("two"))
    }

    @Test
    fun `equal binary properties have stable keys and AMQP headers`() {
        listOf("messageId", "correlationId", "rawKey").forEach { source ->
            val ingest = ingest("rabbitmq.message.key" to source, "rabbitmq.headers.amqp.enabled" to "true")
            repeat(2) { offset ->
                val msg = message(binary = byteArrayOf(9))
                val props = mock(Properties::class.java)
                `when`(props.messageId).thenReturn(byteArrayOf(1, 2, 3))
                `when`(props.correlationId).thenReturn(byteArrayOf(1, 2, 3))
                `when`(msg.properties).thenReturn(props)
                `when`(msg.applicationProperties).thenReturn(mapOf("rawKey" to byteArrayOf(1, 2, 3)))
                ingest.deliver(STREAM, offset.toLong(), msg)
            }
            val records = ingest.poll()
            assertEquals(listOf("AQID", "AQID"), records.map { it.key() })
            records.forEach { record ->
                assertEquals("AQID", record.headers().lastWithName("amqp.messageId").value())
                assertEquals("AQID", record.headers().lastWithName("amqp.correlationId").value())
            }
        }
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
    fun `first state transition has no previous state`() {
        val ingest = ingest()
        ingest.stateChanged(STREAM, null, Resource.State.OPEN)
        assertTrue(ingest.poll().isEmpty())

        ingest.stateChanged(STREAM, null, Resource.State.CLOSED)
        assertThrows(ConnectException::class.java) { ingest.poll() }
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
