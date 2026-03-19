package com.github.maksimgr

import com.rabbitmq.stream.Environment
import io.netty.handler.ssl.SslContextBuilder
import org.apache.kafka.connect.data.Schema
import org.apache.kafka.connect.errors.ConnectException
import org.apache.kafka.connect.source.SourceRecord
import org.apache.kafka.connect.source.SourceTask
import org.slf4j.LoggerFactory
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.TrustManagerFactory

class RabbitSourceTask : SourceTask() {
    companion object {
        @JvmStatic
        val logger = LoggerFactory.getLogger(RabbitSourceTask::class.java)!!
    }

    private lateinit var config: RabbitSourceConfig
    private lateinit var environment: Environment
    private val consumers = CopyOnWriteArrayList<com.rabbitmq.stream.Consumer>()

    private val messageQueue = LinkedBlockingQueue<SourceRecord>(10_000)
    private val running = AtomicBoolean(false)

    override fun version(): String = RabbitSourceConnector.VERSION

    override fun start(props: MutableMap<String, String>) {
        logger.info("Starting RabbitSourceTask")
        try {
            config = RabbitSourceConfig(props)
            val envBuilder =
                Environment
                    .builder()
                    .host(config.getString("rabbitmq.host"))
                    .port(config.getInt("rabbitmq.port"))
                    .username(config.getString("rabbitmq.username"))
                    .password(config.getString("rabbitmq.password"))
                    .virtualHost(config.getString("rabbitmq.virtual.host"))
                    .requestedMaxFrameSize(config.getInt("rabbitmq.requested.frame.max"))
                    .requestedHeartbeat(
                        Duration.ofSeconds(config.getInt("rabbitmq.requested.heartbeat.seconds").toLong()),
                    )

            if (config.getBoolean("rabbitmq.tls.enabled")) {
                val truststorePath = config.getString("rabbitmq.tls.truststore.path")
                val sslContext =
                    if (truststorePath.isNotEmpty()) {
                        val truststorePassword = config.getPassword("rabbitmq.tls.truststore.password").value()
                        val truststore = KeyStore.getInstance("JKS")
                        FileInputStream(truststorePath).use { truststore.load(it, truststorePassword.toCharArray()) }
                        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                        tmf.init(truststore)
                        SslContextBuilder.forClient().trustManager(tmf).build()
                    } else {
                        SslContextBuilder.forClient().build()
                    }
                envBuilder.tls().sslContext(sslContext).hostnameVerification().environmentBuilder()
            }

            environment = envBuilder.build()
            initializeConnection()
            running.set(true)
            logger.info("RabbitSourceTask started")
        } catch (e: Exception) {
            throw ConnectException("Failed to start RabbitSourceTask", e)
        }
    }

    override fun stop() {
        logger.info("Stopping RabbitSourceTask")
        running.set(false)
        consumers.forEach { consumer ->
            try {
                consumer.close()
            } catch (e: Exception) {
                logger.warn("Error closing consumer", e)
            }
        }
        consumers.clear()
        messageQueue.clear()
        try {
            environment.close()
        } catch (e: Exception) {
            logger.warn("Error closing RabbitMQ environment", e)
        }
        logger.info("RabbitSourceTask stopped")
    }

    override fun poll(): MutableList<SourceRecord> {
        val records = mutableListOf<SourceRecord>()
        val first = messageQueue.poll(100, TimeUnit.MILLISECONDS) ?: return records
        records.add(first)
        messageQueue.drainTo(records)
        return records
    }

    private fun initializeConnection() {
        val queueNames = config.getList("rabbitmq.queue").map { it.trim() }.filter { it.isNotEmpty() }
        if (queueNames.isEmpty()) {
            throw IllegalArgumentException("rabbitmq queue must be provided")
        }
        val offsetStr = config.getString("rabbitmq.offset")
        val offsetSpec = RabbitOffsetResolver.resolveOffset(offsetStr)
        logger.info("RabbitSourceTask initializing connection")

        queueNames.forEach { queueName ->
            val consumer =
                environment.consumerBuilder()
                    .stream(queueName)
                    .name("kafka-connector-$queueName")
                    .autoTrackingStrategy().builder()
                    .offset(offsetSpec)
                    .messageHandler { ctx, msg ->
                        try {
                            val offset = ctx.offset()
                            val body = String(msg.bodyAsBinary, StandardCharsets.UTF_8)
                            logger.debug("Received message at offset $offset with body: $body")

                            val record =
                                SourceRecord(
                                    mapOf("queue" to queueName),
                                    mapOf("offset" to offset),
                                    config.getString("kafka.topic"),
                                    null,
                                    null,
                                    null,
                                    Schema.STRING_SCHEMA,
                                    body,
                                )

                            val offered = messageQueue.offer(record, 5, TimeUnit.SECONDS)
                            if (!offered) {
                                logger.warn("Message queue full, dropping message from '$queueName' at offset $offset")
                            }
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            logger.warn("Message handler interrupted for queue '$queueName'")
                        } catch (e: Exception) {
                            logger.error(
                                "Error processing message from queue '$queueName' at offset ${ctx.offset()}",
                                e,
                            )
                        }
                    }
                    .build()
            consumers.add(consumer)
        }
        logger.info("Started consuming RabbitMQ streams: $queueNames from offset: $offsetSpec")
    }
}
