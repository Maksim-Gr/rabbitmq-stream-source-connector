package com.github.maksimgr

import org.apache.kafka.common.config.AbstractConfig
import org.apache.kafka.common.config.ConfigDef
import org.apache.kafka.common.config.ConfigException

class RabbitSourceConfig(
    props: MutableMap<*, *>,
) : AbstractConfig(CONFIG, props) {
    companion object {
        private const val CONFIG_NAME_DESTINATION_KAFKA_TOPIC = "kafka.topic"
        private const val CONFIG_NAME_SOURCE_RABBITMQ_QUEUES = "rabbitmq.queue"
        private const val RABBITMQ_HOST = "rabbitmq.host"
        private const val RABBITMQ_PORT = "rabbitmq.port"
        private const val RABBITMQ_USERNAME = "rabbitmq.username"
        private const val RABBITMQ_PASSWORD = "rabbitmq.password"
        private const val RABBITMQ_VIRTUAL_HOST = "rabbitmq.virtual.host"
        private const val CONFIG_NAME_RABBITMQ_OFFSET = "rabbitmq.offset"
        private const val RABBITMQ_REQUESTED_HEARTBEAT = "rabbitmq.requested.heartbeat.seconds"
        private const val RABBITMQ_REQUESTED_FRAME_MAX = "rabbitmq.requested.frame.max"
        private const val RABBITMQ_TLS_ENABLED = "rabbitmq.tls.enabled"
        private const val RABBITMQ_TLS_TRUSTSTORE_PATH = "rabbitmq.tls.truststore.path"
        private const val RABBITMQ_TLS_TRUSTSTORE_PASSWORD = "rabbitmq.tls.truststore.password"

        private val NON_EMPTY_STRING_VALIDATOR =
            ConfigDef.Validator { name, value ->
                if (value is String && value.trim().isEmpty()) {
                    throw ConfigException(name, value, "Value must not be empty")
                }
            }

        private val PORT_RANGE_VALIDATOR = ConfigDef.Range.between(1, 65535)

        private val OFFSET_VALIDATOR =
            ConfigDef.Validator { name, value ->
                if (value is String) {
                    val normalized = value.trim().lowercase()
                    if (normalized !in setOf("first", "last", "next")) {
                        try {
                            java.time.LocalDateTime.parse(
                                value,
                                java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"),
                            )
                        } catch (e: java.time.format.DateTimeParseException) {
                            throw ConfigException(
                                name,
                                value,
                                "Must be 'first', 'last', 'next', or timestamp format 'dd.MM.yyyy HH:mm:ss'",
                            )
                        }
                    }
                }
            }

        val CONFIG: ConfigDef =
            ConfigDef()
                .define(
                    CONFIG_NAME_DESTINATION_KAFKA_TOPIC,
                    ConfigDef.Type.STRING,
                    ConfigDef.NO_DEFAULT_VALUE,
                    NON_EMPTY_STRING_VALIDATOR,
                    ConfigDef.Importance.HIGH,
                    "Destination Kafka topic where messages are written.",
                    "Settings",
                    -1,
                    ConfigDef.Width.MEDIUM,
                    "Kafka Destination Topic",
                ).define(
                    CONFIG_NAME_SOURCE_RABBITMQ_QUEUES,
                    ConfigDef.Type.LIST,
                    ConfigDef.Importance.HIGH,
                    "Source RabbitMQ queue where messages are pulled.",
                    "Settings",
                    -1,
                    ConfigDef.Width.MEDIUM,
                    "RabbitMQ Source Queues",
                ).define(
                    RABBITMQ_HOST,
                    ConfigDef.Type.STRING,
                    "localhost",
                    NON_EMPTY_STRING_VALIDATOR,
                    ConfigDef.Importance.HIGH,
                    "The name of the RabbitMQ host.",
                    "Settings",
                    -1,
                    ConfigDef.Width.MEDIUM,
                    "RabbitMQ Host",
                ).define(
                    RABBITMQ_PORT,
                    ConfigDef.Type.INT,
                    5552,
                    PORT_RANGE_VALIDATOR,
                    ConfigDef.Importance.MEDIUM,
                    "The port that RabbitMQ will listen on.",
                    "Settings",
                    -1,
                    ConfigDef.Width.SHORT,
                    "RabbitMQ Port",
                ).define(
                    RABBITMQ_USERNAME,
                    ConfigDef.Type.STRING,
                    ConfigDef.NO_DEFAULT_VALUE,
                    ConfigDef.Importance.HIGH,
                    "The username for authenticating with RabbitMQ.",
                    "Settings",
                    -1,
                    ConfigDef.Width.MEDIUM,
                    "RabbitMQ Username",
                ).define(
                    RABBITMQ_PASSWORD,
                    ConfigDef.Type.PASSWORD,
                    ConfigDef.NO_DEFAULT_VALUE,
                    ConfigDef.Importance.HIGH,
                    "The password for authenticating with RabbitMQ.",
                    "Settings",
                    -1,
                    ConfigDef.Width.MEDIUM,
                    "RabbitMQ Password",
                ).define(
                    RABBITMQ_VIRTUAL_HOST,
                    ConfigDef.Type.STRING,
                    "/",
                    ConfigDef.Importance.HIGH,
                    "The virtual host RabbitMQ uses when connecting to the broker.",
                    "Settings",
                    -1,
                    ConfigDef.Width.MEDIUM,
                    "RabbitMQ Virtual Host",
                ).define(
                    CONFIG_NAME_RABBITMQ_OFFSET,
                    ConfigDef.Type.STRING,
                    "first",
                    OFFSET_VALIDATOR,
                    ConfigDef.Importance.MEDIUM,
                    "supports different offset specifications in addition to the absolute offset: first, last, next, and timestamp.",
                    "Advanced Settings",
                    -1,
                    ConfigDef.Width.SHORT,
                    "Offset position",
                ).define(
                    RABBITMQ_REQUESTED_HEARTBEAT,
                    ConfigDef.Type.INT,
                    60,
                    ConfigDef.Importance.MEDIUM,
                    "Requested heartbeat in seconds for the RabbitMQ Streams connection.",
                    "Advanced Settings",
                    -1,
                    ConfigDef.Width.SHORT,
                    "Requested Heartbeat",
                ).define(
                    RABBITMQ_REQUESTED_FRAME_MAX,
                    ConfigDef.Type.INT,
                    1048576,
                    ConfigDef.Importance.MEDIUM,
                    "Maximum frame size requested for the RabbitMQ Streams connection.",
                    "Advanced Settings",
                    -1,
                    ConfigDef.Width.SHORT,
                    "Requested Frame Max",
                ).define(
                    RABBITMQ_TLS_ENABLED,
                    ConfigDef.Type.BOOLEAN,
                    false,
                    ConfigDef.Importance.MEDIUM,
                    "Enable TLS for the RabbitMQ Streams connection.",
                    "TLS",
                    -1,
                    ConfigDef.Width.SHORT,
                    "TLS Enabled",
                ).define(
                    RABBITMQ_TLS_TRUSTSTORE_PATH,
                    ConfigDef.Type.STRING,
                    "",
                    ConfigDef.Importance.MEDIUM,
                    "Path to the JKS truststore file for TLS verification.",
                    "TLS",
                    -1,
                    ConfigDef.Width.MEDIUM,
                    "TLS Truststore Path",
                ).define(
                    RABBITMQ_TLS_TRUSTSTORE_PASSWORD,
                    ConfigDef.Type.PASSWORD,
                    "",
                    ConfigDef.Importance.MEDIUM,
                    "Password for the JKS truststore.",
                    "TLS",
                    -1,
                    ConfigDef.Width.MEDIUM,
                    "TLS Truststore Password",
                )
    }
}
