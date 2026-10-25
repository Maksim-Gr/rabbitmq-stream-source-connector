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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Turns messages delivered by the stream consumers into buffered Kafka records for [poll].
 *
 * [resumeOffsets] holds, for every stream this task consumes, the offset its consumer resumes from, or null when it
 * starts from the configured offset. Stream offsets are contiguous, so a jump past the expected offset is a gap
 * (messages removed, typically by retention, before they reached Kafka) and a jump back is a rewind (redelivery).
 *
 * With `rabbitmq.error.handling=fail`, the first message that cannot become a record latches a failure: nothing after
 * it is buffered, and the next [poll] throws. A full buffer blocks [deliver] (backpressure) until [poll] frees space
 * or [close] is called.
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
         * body: byte arrays as-is, scalars as their UTF-8 string form. Maps and lists (`amqp-value` maps and lists,
         * `amqp-sequence`) are rejected: their string form is not a faithful encoding of the data.
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

        private fun ByteArray.utf8(): String = String(this, StandardCharsets.UTF_8)
    }

    private val streams: Map<String, StreamState> =
        resumeOffsets.mapValues { (stream, offset) -> StreamState(RabbitOffsetResolver.sourcePartition(stream), offset) }
    private val buffer = RecordBuffer(config.bufferMaxRecords, config.bufferMaxBytes)
    private val running = AtomicBoolean(true)

    @Volatile
    private var failure: Throwable? = null

    /** Record and byte usage of the buffer, for logging. */
    val bufferStatus: String get() = buffer.toString()

    /** Called on the consumer thread for every message of [stream]; blocks while the buffer is full. */
    fun deliver(
        stream: String,
        offset: Long,
        message: Message,
    ) {
        val state = checkNotNull(streams[stream]) { "Stream '$stream' is not consumed by this task" }
        // A later record reaching Kafka would commit an offset past the failed message.
        if (failure != null) return
        try {
            logger.debug("Received message at offset {}", offset)
            checkContinuity(stream, state, offset)
            val body = message.bodyBytes()
            enqueue(toRecord(state.partition, offset, message, body), body?.size?.toLong() ?: 0L)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            if (running.get()) {
                val text = "Message handler interrupted for stream '$stream' at offset $offset"
                logger.error(text)
                failure = ConnectException(text, e)
            } else {
                logger.warn("Message handler interrupted for stream '$stream'")
            }
        } catch (e: Exception) {
            when (config.errorHandling) {
                ErrorHandling.SKIP ->
                    logger.error("Skipping message from stream '$stream' at offset $offset (rabbitmq.error.handling=skip)", e)
                ErrorHandling.FAIL -> {
                    logger.error("Error processing message from stream '$stream' at offset $offset", e)
                    failure = e
                }
            }
        }
    }

    /** Called when the consumer of [stream] changes state. */
    fun stateChanged(
        stream: String,
        previous: Resource.State,
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

    /**
     * Returns up to `rabbitmq.poll.max.batch.size` buffered records, waiting briefly for the first one.
     *
     * @throws ConnectException once a message or consumer failure has been latched
     */
    fun poll(): List<SourceRecord> {
        failure?.let { throw ConnectException("RabbitSourceTask failed while processing a message", it) }
        val first = buffer.poll(POLL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) ?: return emptyList()
        val records = mutableListOf(first)
        // Cap the batch so a single poll cannot return an unbounded number of records.
        buffer.drainTo(records, config.pollMaxBatchSize - 1)
        return records
    }

    /** Stops accepting messages, releases any [deliver] blocked on backpressure, and empties the buffer. */
    override fun close() {
        running.set(false)
        buffer.clear()
    }

    private fun checkContinuity(
        stream: String,
        state: StreamState,
        offset: Long,
    ) {
        val expected = state.expectedOffset.getAndSet(offset + 1)
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
    ) {
        // Bounded wait instead of put(): a full buffer must not block close(). A record dropped
        // here was never handed to Kafka, so its offset is not committed and it is re-read on restart.
        while (running.get() && !buffer.offer(record, sizeBytes, ENQUEUE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            // buffer full: keep applying backpressure until space frees up or ingest is closed
        }
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
        props.messageId?.let { headers.addString("amqp.messageId", it.toString()) }
        props.correlationId?.let { headers.addString("amqp.correlationId", it.toString()) }
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
            "messageId" -> msg.properties?.messageId?.toString()
            "correlationId" -> msg.properties?.correlationId?.toString()
            else -> msg.applicationProperties?.get(source)?.toString()
        }
    }
}
