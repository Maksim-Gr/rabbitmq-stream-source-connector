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
                        // Try to parse as timestamp
                        try {
                            java.time.LocalDateTime.parse(value, java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"))
                        } catch (e: Exception) {
                            throw ConfigException(name, value, "Must be 'first', 'last', 'next', or timestamp format 'dd.MM.yyyy HH:mm:ss'")
                        }
                    }
                }
            }

        val CONFIG: ConfigDef =
            ConfigDef()
                .define(
                    // name =
                    CONFIG_NAME_DESTINATION_KAFKA_TOPIC,
                    // type =
                    ConfigDef.Type.STRING,
                    // defaultValue =
                    ConfigDef.NO_DEFAULT_VALUE,
                    // validator =
                    NON_EMPTY_STRING_VALIDATOR,
                    // importance =
                    ConfigDef.Importance.HIGH,
                    // documentation =
                    "Destination Kafka topic where messages are written.",
                    // group =
                    "Settings",
                    // orderInGroup =
                    -1,
                    // width =
                    ConfigDef.Width.MEDIUM,
                    // displayName =
                    "Kafka Destination Topic",
                ).define(
                    // name =
                    CONFIG_NAME_SOURCE_RABBITMQ_QUEUES,
                    // type =
                    ConfigDef.Type.LIST,
                    // importance =
                    ConfigDef.Importance.HIGH,
                    // documentation =
                    "Source RabbitMQ queue where messages are pulled.",
                    // group =
                    "Settings",
                    // orderInGroup =
                    -1,
                    // width =
                    ConfigDef.Width.MEDIUM,
                    // displayName =
                    "RabbitMQ Source Queues",
                ).define(
                    // name =
                    RABBITMQ_HOST,
                    // type =
                    ConfigDef.Type.STRING,
                    // defaultValue =
                    "localhost",
                    // validator =
                    NON_EMPTY_STRING_VALIDATOR,
                    // importance =
                    ConfigDef.Importance.HIGH,
                    // documentation =
                    "The name of the RabbitMQ host.",
                    // group =
                    "Settings",
                    // orderInGroup =
                    -1,
                    // width =
                    ConfigDef.Width.MEDIUM,
                    // displayName =
                    "RabbitMQ Host",
                ).define(
                    // name =
                    RABBITMQ_PORT,
                    // type =
                    ConfigDef.Type.INT,
                    // defaultValue =
                    5552,
                    // validator =
                    PORT_RANGE_VALIDATOR,
                    // importance =
                    ConfigDef.Importance.MEDIUM,
                    // documentation =
                    "The port that RabbitMQ will listen on.",
                    // group =
                    "Settings",
                    // orderInGroup =
                    -1,
                    // width =
                    ConfigDef.Width.SHORT,
                    // displayName =
                    "RabbitMQ Port",
                ).define(
                    // name =
                    RABBITMQ_USERNAME,
                    // type =
                    ConfigDef.Type.STRING,
                    // defaultValue =
                    "guest",
                    // importance =
                    ConfigDef.Importance.HIGH,
                    // documentation =
                    "The username for authenticating with RabbitMQ.",
                    // group =
                    "Settings",
                    // orderInGroup =
                    -1,
                    // width =
                    ConfigDef.Width.MEDIUM,
                    // displayName =
                    "RabbitMQ Username",
                ).define(
                    // name =
                    RABBITMQ_PASSWORD,
                    // type =
                    ConfigDef.Type.PASSWORD,
                    // defaultValue =
                    "guest",
                    // importance =
                    ConfigDef.Importance.HIGH,
                    // documentation =
                    "The password for authenticating with RabbitMQ.",
                    // group =
                    "Settings",
                    // orderInGroup =
                    -1,
                    // width =
                    ConfigDef.Width.MEDIUM,
                    // displayName =
                    "RabbitMQ Password",
                ).define(
                    // name =
                    RABBITMQ_VIRTUAL_HOST,
                    // type =
                    ConfigDef.Type.STRING,
                    // defaultValue =
                    "/",
                    // importance =
                    ConfigDef.Importance.HIGH,
                    // documentation =
                    "The virtual host RabbitMQ uses when connecting to the broker.",
                    // group =
                    "Settings",
                    // orderInGroup =
                    -1,
                    // width =
                    ConfigDef.Width.MEDIUM,
                    // displayName =
                    "RabbitMQ Virtual Host",
                ).define(
                    // name =
                    CONFIG_NAME_RABBITMQ_OFFSET,
                    // type =
                    ConfigDef.Type.STRING,
                    // defaultValue =
                    "first",
                    // validator =
                    OFFSET_VALIDATOR,
                    // importance =
                    ConfigDef.Importance.MEDIUM,
                    // documentation =
                    "supports different offset specifications in addition to the absolute offset: first, last, next, and timestamp.",
                    // group =
                    "Advanced Settings",
                    // orderInGroup =
                    -1,
                    // width =
                    ConfigDef.Width.SHORT,
                    // displayName =
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
                )
                .define(
                    RABBITMQ_REQUESTED_FRAME_MAX,
                    ConfigDef.Type.INT,
                    1048576,
                    ConfigDef.Importance.MEDIUM,
                    "Maximum frame size requested for the RabbitMQ Streams connection.",
                    "Advanced Settings",
                    -1,
                    ConfigDef.Width.SHORT,
                    "Requested Frame Max",
                )
    }
}
