package com.github.maksimgr

import com.rabbitmq.stream.BackOffDelayPolicy
import com.rabbitmq.stream.Environment
import com.rabbitmq.stream.OffsetSpecification
import com.rabbitmq.stream.Resource
import io.netty.handler.ssl.SslContextBuilder
import org.apache.kafka.connect.data.Schema
import org.apache.kafka.connect.errors.ConnectException
import org.apache.kafka.connect.header.ConnectHeaders
import org.apache.kafka.connect.source.SourceRecord
import org.apache.kafka.connect.source.SourceTask
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
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManagerFactory

class RabbitSourceTask : SourceTask() {
    companion object {
        @JvmStatic
        val logger = LoggerFactory.getLogger(RabbitSourceTask::class.java)!!

        private const val DEFAULT_BUFFER_SIZE = 10_000
    }

    private lateinit var config: RabbitSourceConfig
    private lateinit var environment: Environment
    private val consumers = CopyOnWriteArrayList<com.rabbitmq.stream.Consumer>()

    @Volatile
    private var messageQueue = LinkedBlockingQueue<SourceRecord>(DEFAULT_BUFFER_SIZE)
    private var bufferSize = DEFAULT_BUFFER_SIZE
    private var pollMaxBatchSize = 1000
    private lateinit var topic: String
    private var messageFormat = "string"
    private var headersEnabled = false
    private var messageKeySource = ""
    private val running = AtomicBoolean(false)

    // Set when a message handler hits an unrecoverable error. The handler runs on a
    // RabbitMQ client thread, so it cannot fail the task directly; poll() re-throws this
    // on the Connect worker thread instead of silently skipping the offending record.
    @Volatile
    private var failure: Throwable? = null
    private lateinit var queueMonitor: ScheduledExecutorService

    override fun version(): String = RabbitSourceConnector.VERSION

    override fun start(props: MutableMap<String, String>) {
        logger.info("Starting RabbitSourceTask")
        try {
            failure = null
            config = RabbitSourceConfig(props)
            bufferSize = config.getInt("rabbitmq.queue.buffer.size")
            pollMaxBatchSize = config.getInt("rabbitmq.poll.max.batch.size")
            messageQueue = LinkedBlockingQueue(bufferSize)
            topic = config.getString("kafka.topic")
            messageFormat = config.getString("rabbitmq.message.format").lowercase()
            headersEnabled = config.getBoolean("rabbitmq.headers.enabled")
            messageKeySource = config.getString("rabbitmq.message.key").trim()
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
            initializeConnection()
            queueMonitor = Executors.newSingleThreadScheduledExecutor()
            queueMonitor.scheduleAtFixedRate(
                { logger.info("Internal message queue depth: ${messageQueue.size} / $bufferSize") },
                30,
                30,
                TimeUnit.SECONDS,
            )
            running.set(true)
            logger.info("RabbitSourceTask started")
        } catch (e: Exception) {
            if (::environment.isInitialized) {
                try {
                    environment.close()
                } catch (ce: Exception) {
                    logger.warn("Error closing environment after failed start", ce)
                }
            }
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
        val first = messageQueue.poll(100, TimeUnit.MILLISECONDS) ?: return records
        records.add(first)
        // Cap the batch so a single poll cannot return an unbounded number of records.
        messageQueue.drainTo(records, pollMaxBatchSize - 1)
        return records
    }

    private fun initializeConnection() {
        val queueNames = config.getList("rabbitmq.queue").map { it.trim() }.filter { it.isNotEmpty() }
        if (queueNames.isEmpty()) {
            throw IllegalArgumentException("rabbitmq queue must be provided")
        }
        val offsetStr = config.getString("rabbitmq.offset")
        val configOffsetSpec = RabbitOffsetResolver.resolveOffset(offsetStr)
        logger.info("RabbitSourceTask initializing connection")

        queueNames.forEach { queueName ->
            // Prefer the offset committed to Kafka Connect's offset store so the task
            // resumes exactly where Kafka last acknowledged (at-least-once). Only when
            // no committed offset exists do we fall back to the configured start offset.
            val partition = mapOf("queue" to queueName)
            val committedOffset = RabbitOffsetResolver.committedOffset(context.offsetStorageReader().offset(partition))
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
                    // Kafka Connect's offset store is the single source of truth for resume
                    // (see initializeConnection above). Disable server-side offset tracking so
                    // the broker isn't carrying redundant, never-read offset commits.
                    .noTrackingStrategy()
                    .offset(offsetSpec)
                    .messageHandler { ctx, msg ->
                        try {
                            val offset = ctx.offset()
                            logger.debug("Received message at offset $offset")
                            messageQueue.put(buildRecord(partition, offset, msg))
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            logger.warn("Message handler interrupted for queue '$queueName'")
                        } catch (e: Exception) {
                            // Don't swallow-and-skip: dropping a record here would let later
                            // records advance the committed Kafka offset past it, silently
                            // losing data and breaking the at-least-once guarantee. Record the
                            // failure so poll() fails the task instead.
                            logger.error(
                                "Error processing message from queue '$queueName' at offset ${ctx.offset()}",
                                e,
                            )
                            failure = e
                        }
                    }
                    .listeners(
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
                                        logger.error(
                                            "Consumer for '$queueName' closed unexpectedly (previous state: ${ctx.previousState()})",
                                        )
                                    }
                                else -> {}
                            }
                        },
                    )
                    .build()
            consumers.add(consumer)
        }
        logger.info("Started consuming RabbitMQ streams: $queueNames (configured offset: $offsetStr)")
    }

    private fun buildRecord(
        partition: Map<String, String>,
        offset: Long,
        msg: com.rabbitmq.stream.Message,
    ): SourceRecord {
        val sourceOffset = mapOf("offset" to offset)
        val key = resolveKey(msg)
        val keySchema = if (key != null) Schema.STRING_SCHEMA else null

        val (valueSchema, value) =
            if (messageFormat == "bytes") {
                Schema.BYTES_SCHEMA to msg.bodyAsBinary
            } else {
                Schema.STRING_SCHEMA to String(msg.bodyAsBinary, StandardCharsets.UTF_8)
            }

        val headers = ConnectHeaders()
        if (headersEnabled) {
            msg.applicationProperties?.forEach { (name, propValue) ->
                if (propValue != null) headers.addString(name, propValue.toString())
            }
        }

        return SourceRecord(
            partition,
            sourceOffset,
            topic,
            null,
            keySchema,
            key,
            valueSchema,
            value,
            null,
            headers,
        )
    }

    /** Resolves the Kafka record key from the configured message property, or null if unset/absent. */
    private fun resolveKey(msg: com.rabbitmq.stream.Message): String? {
        if (messageKeySource.isEmpty()) return null
        return when (messageKeySource) {
            "messageId" -> msg.properties?.messageId?.toString()
            "correlationId" -> msg.properties?.correlationId?.toString()
            else -> msg.applicationProperties?.get(messageKeySource)?.toString()
        }
    }

    private fun buildSslContext(): io.netty.handler.ssl.SslContext {
        val builder = SslContextBuilder.forClient()

        val truststorePath = config.getString("rabbitmq.tls.truststore.path")
        if (truststorePath.isNotEmpty()) {
            val truststorePassword = config.getPassword("rabbitmq.tls.truststore.password").value()
            val truststore = KeyStore.getInstance("JKS")
            FileInputStream(truststorePath).use { truststore.load(it, truststorePassword.toCharArray()) }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(truststore)
            builder.trustManager(tmf)
        }

        val keystorePath = config.getString("rabbitmq.tls.keystore.path")
        if (keystorePath.isNotEmpty()) {
            val keystorePassword = config.getPassword("rabbitmq.tls.keystore.password").value().toCharArray()
            val keystore = KeyStore.getInstance("JKS")
            FileInputStream(keystorePath).use { keystore.load(it, keystorePassword) }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keystore, keystorePassword)
            builder.keyManager(kmf)
        }

        return builder.build()
    }
}
