package com.github.maksimgr

import com.rabbitmq.stream.BackOffDelayPolicy
import com.rabbitmq.stream.Consumer
import com.rabbitmq.stream.Environment
import com.rabbitmq.stream.OffsetSpecification
import com.rabbitmq.stream.Resource
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import org.apache.kafka.connect.errors.ConnectException
import org.apache.kafka.connect.source.SourceRecord
import org.apache.kafka.connect.source.SourceTask
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.FileInputStream
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManagerFactory

class RabbitSourceTask : SourceTask() {
    companion object {
        @JvmStatic
        val logger: Logger = LoggerFactory.getLogger(RabbitSourceTask::class.java)

        private const val QUEUE_MONITOR_INITIAL_DELAY_SECONDS = 30L
        private const val QUEUE_MONITOR_PERIOD_SECONDS = 30L
    }

    private lateinit var config: RabbitSourceConfig
    private lateinit var environment: Environment
    private val consumers = CopyOnWriteArrayList<Consumer>()

    @Volatile
    private var ingest: StreamIngest? = null
    private lateinit var queueMonitor: ScheduledExecutorService

    override fun version(): String = RabbitSourceConnector.VERSION

    override fun start(props: MutableMap<String, String>) {
        logger.info("Starting RabbitSourceTask")
        try {
            config = RabbitSourceConfig(props)
            val envBuilder =
                Environment
                    .builder()
                    .host(config.host)
                    .port(config.port)
                    .username(config.username)
                    .password(config.password.value())
                    .virtualHost(config.virtualHost)
                    .requestedMaxFrameSize(config.requestedFrameMax)
                    .requestedHeartbeat(config.requestedHeartbeat)
                    .recoveryBackOffDelayPolicy(BackOffDelayPolicy.fixed(config.recoveryBackoff))

            config.tls?.let { tls ->
                envBuilder.tls().sslContext(buildSslContext(tls)).environmentBuilder()
            }

            environment = envBuilder.build()
            initializeConnection()
            queueMonitor = Executors.newSingleThreadScheduledExecutor()
            queueMonitor.scheduleAtFixedRate(
                { logger.info("Internal message queue depth: {}", ingest?.bufferStatus) },
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
        // Before closing consumers: releases their threads if blocked on a full buffer.
        ingest?.close()
        if (::queueMonitor.isInitialized) queueMonitor.shutdown()
        consumers.forEach { consumer ->
            try {
                consumer.close()
            } catch (e: Exception) {
                logger.warn("Error closing consumer", e)
            }
        }
        consumers.clear()
        if (::environment.isInitialized) {
            try {
                environment.close()
            } catch (e: Exception) {
                logger.warn("Error closing RabbitMQ environment", e)
            }
        }
        logger.info("RabbitSourceTask stopped")
    }

    override fun poll(): MutableList<SourceRecord> = checkNotNull(ingest) { "RabbitSourceTask is not started" }.poll().toMutableList()

    private fun initializeConnection() {
        val streams = config.streams
        require(streams.isNotEmpty()) { "rabbitmq queue must be provided" }
        logger.info("RabbitSourceTask initializing connection")

        // Prefer the offset committed to Kafka Connect's offset store so the task
        // resumes exactly where Kafka last acknowledged (at-least-once). Only when
        // no committed offset exists do we fall back to the configured start offset.
        val resumeOffsets =
            streams.associateWith { stream ->
                val stored = context.offsetStorageReader().offset(RabbitOffsetResolver.sourcePartition(stream))
                RabbitOffsetResolver.committedOffset(stored)?.plus(1)
            }
        // Created before the consumers: they dispatch as soon as they are built.
        val ingest = StreamIngest(config, resumeOffsets).also { this.ingest = it }

        streams.forEach { stream ->
            val resumeOffset = resumeOffsets[stream]
            val offsetSpec =
                if (resumeOffset != null) {
                    logger.info("Resuming stream '$stream' from committed offset $resumeOffset")
                    OffsetSpecification.offset(resumeOffset)
                } else {
                    logger.info("No committed offset for stream '$stream'; starting from '${config.configuredOffset}'")
                    config.startOffset
                }

            // Keep unnamed: for a named consumer the client resumes from a broker-stored offset, even with noTrackingStrategy().
            val consumer =
                environment.consumerBuilder()
                    .stream(stream)
                    .noTrackingStrategy()
                    .offset(offsetSpec)
                    .messageHandler { ctx, msg -> ingest.deliver(stream, ctx.offset(), msg) }
                    .listeners(Resource.StateListener { ctx -> ingest.stateChanged(stream, ctx.previousState(), ctx.currentState()) })
                    .build()
            consumers.add(consumer)
        }
        logger.info("Started consuming RabbitMQ streams: $streams (configured offset: ${config.configuredOffset})")
    }

    private fun buildSslContext(tls: TlsSettings): SslContext {
        fun StoreSettings.load(): KeyStore =
            KeyStore.getInstance(type.name).also { store ->
                FileInputStream(path).use { store.load(it, password.value().toCharArray()) }
            }

        val builder = SslContextBuilder.forClient()

        tls.truststore?.let { store ->
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(store.load())
            builder.trustManager(tmf)
        }

        tls.keystore?.let { store ->
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(store.load(), store.password.value().toCharArray())
            builder.keyManager(kmf)
        }

        return builder.build()
    }
}
