package com.github.maksimgr

import com.rabbitmq.stream.Message
import com.rabbitmq.stream.Resource
import org.apache.kafka.connect.data.Schema
import org.apache.kafka.connect.errors.ConnectException
import org.apache.kafka.connect.header.ConnectHeaders
import org.apache.kafka.connect.source.SourceRecord
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * [resumeOffsets] maps each stream to the offset its consumer resumes from, or null when it starts from the
 * configured offset. Stream offsets are contiguous, so a jump past the expected offset means messages were removed
 * (typically by retention) before they reached Kafka, and a jump back means they are redelivered.
 */
class StreamIngest(
    private val config: RabbitSourceConfig,
    resumeOffsets: Map<String, Long?>,
) : AutoCloseable {
    private class StreamState(
        val partition: Map<String, String>,
        resumeOffset: Long?,
    ) {
        val expectedOffset = AtomicLong(resumeOffset ?: NO_EXPECTED_OFFSET)
    }

    companion object {
        @JvmStatic
        val logger: Logger = LoggerFactory.getLogger(StreamIngest::class.java)

        private const val POLL_TIMEOUT_MILLIS = 100L
        private const val ENQUEUE_TIMEOUT_MILLIS = 100L
        private const val NO_EXPECTED_OFFSET = -1L

        /**
         * Returns the message body as bytes. Bodies that are not AMQP binary data (e.g. an AMQP 1.0 `amqp-value`
         * string published by a non-stream client) make [Message.getBodyAsBinary] throw, so fall back to the native
         * body: byte arrays as-is, scalars as their UTF-8 string form. Maps and lists are rejected, since their
         * string form would lose data.
         */
        @Suppress("SwallowedException") // the exception only signals "not binary"; the native body is used instead
        private fun Message.bodyBytes(): ByteArray? =
            try {
                bodyAsBinary
            } catch (e: IllegalStateException) {
                when (val native = body) {
                    null -> null
                    is ByteArray -> native
                    is Map<*, *>, is Collection<*>, is Array<*> ->
                        throw IllegalArgumentException(
                            "Message body is a structured AMQP value (${native.javaClass.simpleName}), which cannot " +
                                "be converted to a Kafka record without losing data",
                        )
                    else -> native.toString().toByteArray(StandardCharsets.UTF_8)
                }
            }

        private fun Any.propertyString(): String = if (this is ByteArray) Base64.getEncoder().encodeToString(this) else toString()

        private fun ByteArray.utf8(): String = String(this, StandardCharsets.UTF_8)
    }

    private val streams: Map<String, StreamState> =
        resumeOffsets.mapValues { (stream, offset) -> StreamState(RabbitOffsetResolver.sourcePartition(stream), offset) }
    private val buffer = RecordBuffer(config.bufferMaxRecords, config.bufferMaxBytes)
    private val running = AtomicBoolean(true)

    @Volatile
    private var failure: Throwable? = null

    val bufferStatus: String get() = buffer.toString()

    /** Blocks while the buffer is full. */
    fun deliver(
        stream: String,
        offset: Long,
        message: Message,
    ) {
        val state = streamState(stream)
        synchronized(state) {
            if (failure != null || !running.get()) return
            // Pin even a first/last/next subscription to its first observed offset before an offer can be interrupted.
            state.expectedOffset.compareAndSet(NO_EXPECTED_OFFSET, offset)
            if (Thread.currentThread().isInterrupted) return
            try {
                logger.debug("Received message at offset {}", offset)
                checkContinuity(stream, state, offset)
                val body = message.bodyBytes()
                if (enqueue(toRecord(state.partition, offset, message, body), body?.size?.toLong() ?: 0L)) {
                    state.expectedOffset.set(offset + 1)
                }
            } catch (e: InterruptedException) {
                // The client interrupts its delivery executor on connection loss. Leave this offset pending so
                // the subscription listener replays it after recovery, and ignore remaining callbacks on this thread.
                Thread.currentThread().interrupt()
                logger.info("Delivery interrupted for stream '{}' at offset {}; leaving it pending for recovery", stream, offset)
            } catch (e: Exception) {
                when (config.errorHandling) {
                    ErrorHandling.SKIP -> {
                        logger.error("Skipping message from stream '$stream' at offset $offset (rabbitmq.error.handling=skip)", e)
                        state.expectedOffset.set(offset + 1)
                    }
                    ErrorHandling.FAIL -> {
                        logger.error("Error processing message from stream '$stream' at offset $offset", e)
                        failure = e
                    }
                }
            }
        }
    }

    /** In-process recovery resumes after buffered/skipped messages; task restarts still use committed offsets. */
    fun subscriptionOffset(stream: String): Long? {
        val state = streamState(stream)
        return synchronized(state) { state.expectedOffset.get().takeUnless { it == NO_EXPECTED_OFFSET } }
    }

    private fun streamState(stream: String): StreamState = checkNotNull(streams[stream]) { "Stream '$stream' is not consumed by this task" }

    /** [previous] is null on a consumer's first transition. */
    fun stateChanged(
        stream: String,
        previous: Resource.State?,
        current: Resource.State,
    ) {
        when (current) {
            Resource.State.RECOVERING ->
                logger.warn("Consumer for '$stream' is recovering (previous state: $previous)")
            Resource.State.OPEN ->
                if (previous == Resource.State.RECOVERING) {
                    logger.info("Consumer for '$stream' recovered successfully")
                }
            Resource.State.CLOSED ->
                if (running.get()) {
                    val text = "Consumer for '$stream' closed unexpectedly (previous state: $previous)"
                    logger.error(text)
                    // Fail the task on the next poll() so Connect reports FAILED instead of a RUNNING
                    // task that no longer receives messages.
                    failure = ConnectException(text)
                }
            else -> {}
        }
    }

    fun poll(): List<SourceRecord> {
        failure?.let { throw ConnectException("RabbitSourceTask failed while processing a message", it) }
        val first = buffer.poll(POLL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) ?: return emptyList()
        val records = mutableListOf(first)
        // Cap the batch so a single poll cannot return an unbounded number of records.
        buffer.drainTo(records, config.pollMaxBatchSize - 1)
        return records
    }

    override fun close() {
        running.set(false)
        buffer.clear()
    }

    private fun checkContinuity(
        stream: String,
        state: StreamState,
        offset: Long,
    ) {
        val expected = state.expectedOffset.get()
        if (expected == NO_EXPECTED_OFFSET) return
        if (offset > expected) {
            logger.warn(
                "Gap in stream '$stream': expected offset $expected but received $offset. " +
                    "${offset - expected} message(s) are no longer in the stream (likely removed by retention) " +
                    "and were not delivered to Kafka.",
            )
        } else if (offset < expected) {
            logger.warn(
                "Stream '$stream' rewound: expected offset $expected but received $offset. " +
                    "${expected - offset} message(s) will be delivered to Kafka again.",
            )
        }
    }

    private fun enqueue(
        record: SourceRecord,
        sizeBytes: Long,
    ): Boolean {
        // Bounded wait instead of put(): a full buffer must not block close(). A record dropped
        // here was never handed to Kafka, so its offset is not committed and it is re-read on restart.
        while (running.get()) {
            if (buffer.offer(record, sizeBytes, ENQUEUE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) return true
        }
        return false
    }

    private fun toRecord(
        partition: Map<String, String>,
        offset: Long,
        msg: Message,
        body: ByteArray?,
    ): SourceRecord {
        val key = resolveKey(msg)
        val keySchema = if (key != null) Schema.STRING_SCHEMA else null

        val (valueSchema, value) =
            when (config.messageFormat) {
                MessageFormat.BYTES -> if (body == null) Schema.OPTIONAL_BYTES_SCHEMA to null else Schema.BYTES_SCHEMA to body
                MessageFormat.STRING -> if (body == null) Schema.OPTIONAL_STRING_SCHEMA to null else Schema.STRING_SCHEMA to body.utf8()
            }

        val headers = ConnectHeaders()
        if (config.headersEnabled) {
            msg.applicationProperties?.forEach { (name, propValue) ->
                when (propValue) {
                    null -> {}
                    is ByteArray -> headers.addBytes(name, propValue)
                    else -> headers.addString(name, propValue.toString())
                }
            }
        }
        if (config.amqpHeadersEnabled) {
            addAmqpHeaders(headers, msg)
        }

        val timestamp = msg.properties?.creationTime?.takeIf { it > 0 }

        return SourceRecord(
            partition,
            RabbitOffsetResolver.sourceOffset(offset),
            config.topic,
            null,
            keySchema,
            key,
            valueSchema,
            value,
            timestamp,
            headers,
        )
    }

    /** Copies standard AMQP message properties onto [headers], prefixed with 'amqp.'. */
    private fun addAmqpHeaders(
        headers: ConnectHeaders,
        msg: Message,
    ) {
        val props = msg.properties ?: return
        props.messageId?.let { headers.addString("amqp.messageId", it.propertyString()) }
        props.correlationId?.let { headers.addString("amqp.correlationId", it.propertyString()) }
        props.contentType?.let { headers.addString("amqp.contentType", it) }
        props.contentEncoding?.let { headers.addString("amqp.contentEncoding", it) }
        props.to?.let { headers.addString("amqp.to", it) }
        props.subject?.let { headers.addString("amqp.subject", it) }
        props.replyTo?.let { headers.addString("amqp.replyTo", it) }
        props.groupId?.let { headers.addString("amqp.groupId", it) }
        props.creationTime.takeIf { it > 0 }?.let { headers.addLong("amqp.creationTime", it) }
    }

    /** Resolves the Kafka record key from the configured message property, or null if unset/absent. */
    private fun resolveKey(msg: Message): String? {
        val source = config.messageKeySource ?: return null
        return when (source) {
            "messageId" -> msg.properties?.messageId?.propertyString()
            "correlationId" -> msg.properties?.correlationId?.propertyString()
            else -> msg.applicationProperties?.get(source)?.propertyString()
        }
    }
}
