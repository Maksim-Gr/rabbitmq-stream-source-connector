package com.github.maksimgr

import org.apache.kafka.common.config.ConfigDef
import org.apache.kafka.connect.connector.Task
import org.apache.kafka.connect.source.SourceConnector
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.Properties

class RabbitSourceConnector : SourceConnector() {
    companion object {
        @JvmStatic
        val logger: Logger = LoggerFactory.getLogger(RabbitSourceConnector::class.java)

        /**
         * Connector version, read from the generated version.properties on the
         * classpath (see the generateVersion task in build.gradle.kts). Falls
         * back to "unknown" if the resource is missing, e.g. when running from
         * an IDE without the generated resources.
         */
        val VERSION: String =
            runCatching {
                RabbitSourceConnector::class.java
                    .getResourceAsStream("/version.properties")
                    ?.use { stream ->
                        Properties().apply { load(stream) }.getProperty("Connector-Version")
                    }
            }.getOrNull() ?: "unknown"
    }

    private lateinit var settings: Map<String, String>

    override fun version(): String = VERSION

    override fun start(props: MutableMap<String, String>) {
        logger.info("Starting RabbitSourceConnector...")
        settings = props
    }

    override fun taskClass(): Class<out Task> = RabbitSourceTask::class.java

    override fun taskConfigs(maxTasks: Int): List<Map<String, String>> {
        val cfg = RabbitSourceConfig(settings.toMutableMap())
        val queues: List<String> = cfg.getList("rabbitmq.queue").distinct()
        if (queues.isEmpty()) {
            logger.info("no queues configured")
            return emptyList()
        }
        val numTasks = minOf(queues.size, maxTasks)
        if (numTasks == 0) return emptyList()

        val taskConfigs =
            MutableList(numTasks) {
                HashMap(settings).apply {
                    remove("rabbitmq.queue")
                }
            }

        queues.forEachIndexed { index, queue ->
            val taskIndex = index % numTasks
            val taskCfg = taskConfigs[taskIndex]

            val existing = taskCfg["rabbitmq.queue"]
            taskCfg["rabbitmq.queue"] = existing?.let { "$it,$queue" } ?: queue
        }
        return taskConfigs
    }

    override fun stop() {
        logger.info("Stopping RabbitSourceConnector at ${Instant.now()}")
    }

    override fun config(): ConfigDef = RabbitSourceConfig.CONFIG
}
