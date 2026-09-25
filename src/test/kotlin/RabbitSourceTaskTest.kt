package com.github.maksimgr

import com.rabbitmq.stream.Message
import com.rabbitmq.stream.MessageHandler
import com.rabbitmq.stream.Resource
import org.apache.kafka.connect.data.Schema
import org.apache.kafka.connect.errors.ConnectException
import org.apache.kafka.connect.source.SourceRecord
import org.apache.kafka.connect.source.SourceTaskContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.lang.reflect.Field
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class RabbitSourceTaskTest {
    private lateinit var task: RabbitSourceTask

    @BeforeEach
    fun setUp() {
        task = RabbitSourceTask()
        task.initialize(mock(SourceTaskContext::class.java))
        setSettings(task)
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
        setSettings(task, pollMaxBatchSize = 3)
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
        val error = assertThrows(ConnectException::class.java) { task.poll() }
        assertEquals("boom", error.cause?.message)
    }

    @Test
    fun `message handler enqueues a record for a binary body`() {
        setRunning(task, true)
        handler().handle(context(5), message(binary = "hello".toByteArray()))

        val record = task.poll().single()
        assertEquals("hello", record.value())
        assertEquals(mapOf("offset" to 5L), record.sourceOffset())
    }

    @Test
    fun `message handler converts a non-binary amqp-value body to its string form`() {
        setRunning(task, true)
        handler().handle(context(0), message(binary = null, native = "from amqp 1.0", binaryThrows = true))

        assertEquals("from amqp 1.0", task.poll().single().value())
    }

    @Test
    fun `message handler emits a null value with an optional schema for an empty body`() {
        setRunning(task, true)
        handler().handle(context(0), message(binary = null))

        val record = task.poll().single()
        assertNull(record.value())
        assertTrue(record.valueSchema().isOptional)
    }

    @Test
    fun `failing message fails the task when error handling is fail`() {
        setRunning(task, true)
        handler().handle(context(7), poisonMessage())

        assertThrows(ConnectException::class.java) { task.poll() }
    }

    @Test
    fun `failing message is skipped when error handling is skip`() {
        setSettings(task, skipFailedMessages = true)
        setRunning(task, true)
        val handler = handler()
        handler.handle(context(7), poisonMessage())
        handler.handle(context(8), message(binary = "ok".toByteArray()))

        val records = task.poll()
        assertEquals(listOf("ok"), records.map { it.value() })
    }

    @Test
    fun `message handler keeps delivering after an offset gap`() {
        setRunning(task, true)
        val handler = handler(expectedOffset = 10)
        handler.handle(context(15), message(binary = "after-gap".toByteArray()))

        assertEquals("after-gap", task.poll().single().value())
    }

    @Test
    fun `stop does not hang when the handler is blocked on a full buffer`() {
        setSettings(task, bufferSize = 1)
        setMessageQueue(task, LinkedBlockingQueue(1))
        setRunning(task, true)
        val handler = handler()
        handler.handle(context(0), message(binary = "fills-buffer".toByteArray()))

        val finished = AtomicBoolean(false)
        val blocked =
            Thread {
                handler.handle(context(1), message(binary = "blocked".toByteArray()))
                finished.set(true)
            }.apply { start() }

        blocked.join(300)
        assertFalse(finished.get(), "handler should apply backpressure while the buffer is full")

        task.stop()
        blocked.join(2_000)
        assertTrue(finished.get(), "handler should return once the task is stopped")
    }

    @Test
    fun `consumer closing unexpectedly fails the task`() {
        setRunning(task, true)
        stateListener().handle(stateContext(Resource.State.CLOSED, Resource.State.OPEN))

        val error = assertThrows(ConnectException::class.java) { task.poll() }
        assertTrue(error.cause?.message.orEmpty().contains("closed unexpectedly"))
    }

    @Test
    fun `consumer closing during stop does not fail the task`() {
        setRunning(task, false)
        stateListener().handle(stateContext(Resource.State.CLOSED, Resource.State.OPEN))

        assertTrue(task.poll().isEmpty())
    }

    private fun handler(expectedOffset: Long = -1): MessageHandler {
        val method =
            RabbitSourceTask::class.java.getDeclaredMethod(
                "buildMessageHandler",
                String::class.java,
                Map::class.java,
                Long::class.javaPrimitiveType,
            )
        method.isAccessible = true
        return method.invoke(task, "test", mapOf("queue" to "test"), expectedOffset) as MessageHandler
    }

    private fun stateListener(): Resource.StateListener {
        val method = RabbitSourceTask::class.java.getDeclaredMethod("buildStateListener", String::class.java)
        method.isAccessible = true
        return method.invoke(task, "test") as Resource.StateListener
    }

    private fun context(offset: Long): MessageHandler.Context =
        mock(MessageHandler.Context::class.java).also { `when`(it.offset()).thenReturn(offset) }

    private fun stateContext(
        current: Resource.State,
        previous: Resource.State,
    ): Resource.Context =
        mock(Resource.Context::class.java).also {
            `when`(it.currentState()).thenReturn(current)
            `when`(it.previousState()).thenReturn(previous)
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

    private fun setRunning(
        task: RabbitSourceTask,
        value: Boolean,
    ) {
        val field: Field = RabbitSourceTask::class.java.getDeclaredField("running")
        field.isAccessible = true
        (field.get(task) as AtomicBoolean).set(value)
    }

    private fun setMessageQueue(
        task: RabbitSourceTask,
        queue: LinkedBlockingQueue<SourceRecord>,
    ) {
        val field: Field = RabbitSourceTask::class.java.getDeclaredField("messageQueue")
        field.isAccessible = true
        field.set(task, queue)
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

    private fun setSettings(
        task: RabbitSourceTask,
        pollMaxBatchSize: Int = 1000,
        bufferSize: Int = 10_000,
        skipFailedMessages: Boolean = false,
    ) {
        val messageFormatClass = Class.forName("com.github.maksimgr.RabbitSourceTask\$MessageFormat")

        @Suppress("UNCHECKED_CAST")
        val stringFormat = messageFormatClass.getMethod("valueOf", String::class.java).invoke(null, "STRING")

        val settingsClass = Class.forName("com.github.maksimgr.RabbitSourceTask\$TaskSettings")
        val constructor = settingsClass.declaredConstructors.first()
        constructor.isAccessible = true
        // bufferSize, pollMaxBatchSize, topic, messageFormat, headersEnabled, amqpHeadersEnabled, messageKeySource,
        // skipFailedMessages
        val settings =
            constructor.newInstance(bufferSize, pollMaxBatchSize, "test-topic", stringFormat, false, false, null, skipFailedMessages)
        val field: Field = RabbitSourceTask::class.java.getDeclaredField("settings")
        field.isAccessible = true
        field.set(task, settings)
    }
}
