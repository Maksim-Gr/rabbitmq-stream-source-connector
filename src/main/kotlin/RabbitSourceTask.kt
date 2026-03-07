package com.github.maksimgr

import com.rabbitmq.stream.Environment
import org.apache.kafka.connect.data.Schema
import org.apache.kafka.connect.source.SourceRecord
import org.apache.kafka.connect.source.SourceTask
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

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

        config = RabbitSourceConfig(props)
        environment =
            Environment
                .builder()
                .host(config.getString("rabbitmq.host"))
                .port(config.getInt("rabbitmq.port"))
                .username(config.getString("rabbitmq.username"))
                .password(config.getString("rabbitmq.password"))
                .virtualHost(config.getString("rabbitmq.virtual.host"))
                .requestedMaxFrameSize(config.getInt("rabbitmq.requested.frame.max"))
                .requestedHeartbeat(Duration.ofSeconds(config.getInt("rabbitmq.requested.heartbeat.seconds").toLong()))
                .build()
        initializeConnection()
        running.set(true)

        logger.info("RabbitSourceTask started")
    }

    override fun stop() {
        logger.info("Stopping RabbitSourceTask")
        running.set(false)
        consumers.forEach { it.close() }
        consumers.clear()
        messageQueue.clear()
        environment.close()
        logger.info("RabbitSourceTask stopped")
    }

    override fun poll(): MutableList<SourceRecord> {
        val records = mutableListOf<SourceRecord>()

        var record = messageQueue.poll()
        while (record != null && running.get()) {
            records.add(record)
            record = messageQueue.poll()
        }

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

                            messageQueue.put(record)
                        } catch (e: Exception) {
                            logger.error("Error processing message from queue '$queueName' at offset ${ctx.offset()}", e)
                        }
                    }
                    .build()
            consumers.add(consumer)
        }
        logger.info("Started consuming RabbitMQ streams: $queueNames from offset: $offsetSpec")
    }
}
