package com.github.maksimgr

import com.rabbitmq.stream.BackOffDelayPolicy
import com.rabbitmq.stream.Consumer
import com.rabbitmq.stream.Environment
import com.rabbitmq.stream.Message
import com.rabbitmq.stream.MessageHandler
import com.rabbitmq.stream.OffsetSpecification
import com.rabbitmq.stream.Resource
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import org.apache.kafka.connect.data.Schema
import org.apache.kafka.connect.errors.ConnectException
import org.apache.kafka.connect.header.ConnectHeaders
import org.apache.kafka.connect.source.SourceRecord
import org.apache.kafka.connect.source.SourceTask
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManagerFactory

class RabbitSourceTask : SourceTask() {
    private enum class MessageFormat { STRING, BYTES }

    private data class TaskSettings(
        val bufferSize: Int,
        val pollMaxBatchSize: Int,
        val topic: String,
        val messageFormat: MessageFormat,
        val headersEnabled: Boolean,
        val amqpHeadersEnabled: Boolean,
        val messageKeySource: String?,
        val skipFailedMessages: Boolean,
    )

    companion object {
        @JvmStatic
        val logger: Logger = LoggerFactory.getLogger(RabbitSourceTask::class.java)

        private const val DEFAULT_BUFFER_SIZE = 10_000
        private const val QUEUE_MONITOR_INITIAL_DELAY_SECONDS = 30L
        private const val QUEUE_MONITOR_PERIOD_SECONDS = 30L
        private const val POLL_TIMEOUT_MILLIS = 100L
        private const val ENQUEUE_TIMEOUT_MILLIS = 100L
        private const val NO_EXPECTED_OFFSET = -1L

        /**
         * Returns the message body as bytes. Bodies that are not AMQP binary data (e.g. an AMQP 1.0
         * `amqp-value` string published by a non-stream client) make [Message.getBodyAsBinary] throw,
         * so fall back to the native body: byte arrays as-is, anything else as its UTF-8 string form.
         */
        @Suppress("SwallowedException") // the exception only signals "not binary"; the native body is used instead
        private fun Message.bodyBytes(): ByteArray? =
            try {
                bodyAsBinary
            } catch (e: IllegalStateException) {
                when (val native = body) {
                    null -> null
                    is ByteArray -> native
                    else -> native.toString().toByteArray(StandardCharsets.UTF_8)
                }
            }
    }

    private lateinit var config: RabbitSourceConfig
    private lateinit var environment: Environment
    private lateinit var settings: TaskSettings
    private val consumers = CopyOnWriteArrayList<Consumer>()

    @Volatile
    private var messageQueue = LinkedBlockingQueue<SourceRecord>(DEFAULT_BUFFER_SIZE)

    // Set before consumers are built: they dispatch immediately and the message handler only enqueues while running.
    private val running = AtomicBoolean(false)

    @Volatile
    private var failure: Throwable? = null
    private lateinit var queueMonitor: ScheduledExecutorService

    override fun version(): String = RabbitSourceConnector.VERSION

    override fun start(props: MutableMap<String, String>) {
        logger.info("Starting RabbitSourceTask")
        try {
            failure = null
            config = RabbitSourceConfig(props)
            settings =
                TaskSettings(
                    bufferSize = config.getInt("rabbitmq.queue.buffer.size"),
                    pollMaxBatchSize = config.getInt("rabbitmq.poll.max.batch.size"),
                    topic = config.getString("kafka.topic"),
                    messageFormat =
                        when (config.getString("rabbitmq.message.format").trim().lowercase()) {
                            "bytes" -> MessageFormat.BYTES
                            else -> MessageFormat.STRING
                        },
                    headersEnabled = config.getBoolean("rabbitmq.headers.enabled"),
                    amqpHeadersEnabled = config.getBoolean("rabbitmq.headers.amqp.enabled"),
                    messageKeySource = config.getString("rabbitmq.message.key").trim().ifEmpty { null },
                    skipFailedMessages = config.getString("rabbitmq.error.tolerance").trim().lowercase() == "all",
                )
            messageQueue = LinkedBlockingQueue(settings.bufferSize)
            val recoveryBackoff = config.getInt("rabbitmq.recovery.backoff.seconds").toLong()
            val envBuilder =
                Environment
                    .builder()
                    .host(config.getString("rabbitmq.host"))
                    .port(config.getInt("rabbitmq.port"))
                    .username(config.getString("rabbitmq.username"))
                    .password(config.getPassword("rabbitmq.password").value())
                    .virtualHost(config.getString("rabbitmq.virtual.host"))
                    .requestedMaxFrameSize(config.getInt("rabbitmq.requested.frame.max"))
                    .requestedHeartbeat(
                        Duration.ofSeconds(config.getInt("rabbitmq.requested.heartbeat.seconds").toLong()),
                    )
                    .recoveryBackOffDelayPolicy(BackOffDelayPolicy.fixed(Duration.ofSeconds(recoveryBackoff)))

            if (config.getBoolean("rabbitmq.tls.enabled")) {
                envBuilder.tls().sslContext(buildSslContext()).environmentBuilder()
            }

            environment = envBuilder.build()
            running.set(true)
            initializeConnection()
            queueMonitor = Executors.newSingleThreadScheduledExecutor()
            queueMonitor.scheduleAtFixedRate(
                { logger.info("Internal message queue depth: ${messageQueue.size} / ${settings.bufferSize}") },
                QUEUE_MONITOR_INITIAL_DELAY_SECONDS,
                QUEUE_MONITOR_PERIOD_SECONDS,
                TimeUnit.SECONDS,
            )
            logger.info("RabbitSourceTask started")
        } catch (e: Exception) {
            // Release whatever was created before the failure (consumers, environment, monitor).
            stop()
            throw ConnectException("Failed to start RabbitSourceTask", e)
        }
    }

    override fun stop() {
        logger.info("Stopping RabbitSourceTask")
        running.set(false)
        if (::queueMonitor.isInitialized) queueMonitor.shutdown()
        consumers.forEach { consumer ->
            try {
                consumer.close()
            } catch (e: Exception) {
                logger.warn("Error closing consumer", e)
            }
        }
        consumers.clear()
        messageQueue.clear()
        if (::environment.isInitialized) {
            try {
                environment.close()
            } catch (e: Exception) {
                logger.warn("Error closing RabbitMQ environment", e)
            }
        }
        logger.info("RabbitSourceTask stopped")
    }

    override fun poll(): MutableList<SourceRecord> {
        failure?.let { throw ConnectException("RabbitSourceTask failed while processing a message", it) }
        val records = mutableListOf<SourceRecord>()
        val first = messageQueue.poll(POLL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) ?: return records
        records.add(first)
        // Cap the batch so a single poll cannot return an unbounded number of records.
        messageQueue.drainTo(records, settings.pollMaxBatchSize - 1)
        return records
    }

    private fun initializeConnection() {
        val queueNames = config.getList("rabbitmq.queue").map { it.trim() }.filter { it.isNotEmpty() }
        require(queueNames.isNotEmpty()) { "rabbitmq queue must be provided" }
        val offsetStr = config.getString("rabbitmq.offset")
        val configOffsetSpec = RabbitOffsetResolver.resolveOffset(offsetStr)
        logger.info("RabbitSourceTask initializing connection")

        queueNames.forEach { queueName ->
            // Prefer the offset committed to Kafka Connect's offset store so the task
            // resumes exactly where Kafka last acknowledged (at-least-once). Only when
            // no committed offset exists do we fall back to the configured start offset.
            val partition = mapOf("queue" to queueName)
            val committedOffset = RabbitOffsetResolver.committedOffset(context.offsetStorageReader().offset(partition))
            val expectedOffset = committedOffset?.plus(1) ?: NO_EXPECTED_OFFSET
            val offsetSpec =
                if (committedOffset != null) {
                    logger.info("Resuming queue '$queueName' from committed offset ${committedOffset + 1}")
                    OffsetSpecification.offset(committedOffset + 1)
                } else {
                    logger.info("No committed offset for queue '$queueName'; starting from '$offsetStr'")
                    configOffsetSpec
                }

            val consumer =
                environment.consumerBuilder()
                    .stream(queueName)
                    .name("kafka-connector-$queueName")
                    .noTrackingStrategy()
                    .offset(offsetSpec)
                    .messageHandler(buildMessageHandler(queueName, partition, expectedOffset))
                    .listeners(buildStateListener(queueName))
                    .build()
            consumers.add(consumer)
        }
        logger.info("Started consuming RabbitMQ streams: $queueNames (configured offset: $offsetStr)")
    }

    /**
     * [initialExpectedOffset] is the offset the task asked to resume from, or [NO_EXPECTED_OFFSET]
     * when starting from the configured `rabbitmq.offset`. Stream offsets are contiguous, so a jump
     * past the expected offset means messages were removed (typically by retention) before they
     * reached Kafka.
     */
    private fun buildMessageHandler(
        queueName: String,
        partition: Map<String, String>,
        initialExpectedOffset: Long,
    ): MessageHandler {
        val expectedOffset = AtomicLong(initialExpectedOffset)
        return MessageHandler { ctx, msg ->
            val offset = ctx.offset()
            try {
                logger.debug("Received message at offset $offset")
                val expected = expectedOffset.getAndSet(offset + 1)
                if (expected != NO_EXPECTED_OFFSET && offset > expected) {
                    logger.warn(
                        "Gap in stream '$queueName': expected offset $expected but received $offset. " +
                            "${offset - expected} message(s) are no longer in the stream (likely removed by retention) " +
                            "and were not delivered to Kafka.",
                    )
                }
                val record = buildRecord(partition, offset, msg)
                // Bounded wait instead of put(): a full buffer must not block stop(). A record dropped
                // here was never handed to Kafka, so its offset is not committed and it is re-read on restart.
                while (running.get() && !messageQueue.offer(record, ENQUEUE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                    // buffer full: keep applying backpressure until space frees up or the task stops
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                logger.warn("Message handler interrupted for queue '$queueName'")
            } catch (e: Exception) {
                if (settings.skipFailedMessages) {
                    logger.error("Skipping message from queue '$queueName' at offset $offset (rabbitmq.error.tolerance=all)", e)
                } else {
                    logger.error("Error processing message from queue '$queueName' at offset $offset", e)
                    failure = e
                }
            }
        }
    }

    private fun buildStateListener(queueName: String) =
        Resource.StateListener { ctx ->
            when (ctx.currentState()) {
                Resource.State.RECOVERING ->
                    logger.warn("Consumer for '$queueName' is recovering (previous state: ${ctx.previousState()})")
                Resource.State.OPEN ->
                    if (ctx.previousState() == Resource.State.RECOVERING) {
                        logger.info("Consumer for '$queueName' recovered successfully")
                    }
                Resource.State.CLOSED ->
                    if (running.get()) {
                        val message = "Consumer for '$queueName' closed unexpectedly (previous state: ${ctx.previousState()})"
                        logger.error(message)
                        // Fail the task on the next poll() so Connect reports FAILED instead of a RUNNING
                        // task that no longer receives messages.
                        failure = ConnectException(message)
                    }
                else -> {}
            }
        }

    private fun buildRecord(
        partition: Map<String, String>,
        offset: Long,
        msg: Message,
    ): SourceRecord {
        val sourceOffset = mapOf("offset" to offset)
        val key = resolveKey(msg)
        val keySchema = if (key != null) Schema.STRING_SCHEMA else null

        val body = msg.bodyBytes()
        val (valueSchema, value) =
            when {
                body == null && settings.messageFormat == MessageFormat.BYTES -> Schema.OPTIONAL_BYTES_SCHEMA to null
                body == null -> Schema.OPTIONAL_STRING_SCHEMA to null
                settings.messageFormat == MessageFormat.BYTES -> Schema.BYTES_SCHEMA to body
                else -> Schema.STRING_SCHEMA to String(body, StandardCharsets.UTF_8)
            }

        val headers = ConnectHeaders()
        if (settings.headersEnabled) {
            msg.applicationProperties?.forEach { (name, propValue) ->
                if (propValue != null) headers.addString(name, propValue.toString())
            }
        }
        if (settings.amqpHeadersEnabled) {
            addAmqpHeaders(headers, msg)
        }

        val timestamp = msg.properties?.creationTime?.takeIf { it > 0 }

        return SourceRecord(
            partition,
            sourceOffset,
            settings.topic,
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
        val source = settings.messageKeySource ?: return null
        return when (source) {
            "messageId" -> msg.properties?.messageId?.toString()
            "correlationId" -> msg.properties?.correlationId?.toString()
            else -> msg.applicationProperties?.get(source)?.toString()
        }
    }

    private fun buildSslContext(): SslContext {
        val builder = SslContextBuilder.forClient()

        val truststorePath = config.getString("rabbitmq.tls.truststore.path")
        if (truststorePath.isNotEmpty()) {
            val truststorePassword = config.getPassword("rabbitmq.tls.truststore.password").value()
            val truststoreType = config.getString("rabbitmq.tls.truststore.type").trim().uppercase()
            val truststore = KeyStore.getInstance(truststoreType)
            FileInputStream(truststorePath).use { truststore.load(it, truststorePassword.toCharArray()) }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(truststore)
            builder.trustManager(tmf)
        }

        val keystorePath = config.getString("rabbitmq.tls.keystore.path")
        if (keystorePath.isNotEmpty()) {
            val keystorePassword = config.getPassword("rabbitmq.tls.keystore.password").value().toCharArray()
            val keystoreType = config.getString("rabbitmq.tls.keystore.type").trim().uppercase()
            val keystore = KeyStore.getInstance(keystoreType)
            FileInputStream(keystorePath).use { keystore.load(it, keystorePassword) }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keystore, keystorePassword)
            builder.keyManager(kmf)
        }

        return builder.build()
    }
}
