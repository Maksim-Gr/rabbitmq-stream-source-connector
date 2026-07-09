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
        private const val RABBITMQ_TLS_KEYSTORE_PATH = "rabbitmq.tls.keystore.path"
        private const val RABBITMQ_TLS_KEYSTORE_PASSWORD = "rabbitmq.tls.keystore.password"
        private const val RABBITMQ_TLS_TRUSTSTORE_TYPE = "rabbitmq.tls.truststore.type"
        private const val RABBITMQ_TLS_KEYSTORE_TYPE = "rabbitmq.tls.keystore.type"
        private const val RABBITMQ_MESSAGE_FORMAT = "rabbitmq.message.format"
        private const val RABBITMQ_HEADERS_ENABLED = "rabbitmq.headers.enabled"
        private const val RABBITMQ_HEADERS_AMQP_ENABLED = "rabbitmq.headers.amqp.enabled"
        private const val RABBITMQ_MESSAGE_KEY = "rabbitmq.message.key"
        private const val RABBITMQ_QUEUE_BUFFER_SIZE = "rabbitmq.queue.buffer.size"
        private const val RABBITMQ_RECOVERY_BACKOFF_SECONDS = "rabbitmq.recovery.backoff.seconds"
        private const val RABBITMQ_POLL_MAX_BATCH_SIZE = "rabbitmq.poll.max.batch.size"

        private val MESSAGE_FORMAT_VALIDATOR =
            ConfigDef.Validator { name, value ->
                if (value is String && value.trim().lowercase() !in setOf("string", "bytes")) {
                    throw ConfigException(name, value, "Must be 'string' or 'bytes'")
                }
            }

        private val NON_EMPTY_STRING_VALIDATOR =
            ConfigDef.Validator { name, value ->
                if (value is String && value.trim().isEmpty()) {
                    throw ConfigException(name, value, "Value must not be empty")
                }
            }

        private val PORT_RANGE_VALIDATOR = ConfigDef.Range.between(1, 65535)

        private val KEYSTORE_TYPE_VALIDATOR =
            ConfigDef.Validator { name, value ->
                if (value is String && value.trim().uppercase() !in setOf("JKS", "PKCS12")) {
                    throw ConfigException(name, value, "Must be 'JKS' or 'PKCS12'")
                }
            }

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
                    "Password for the truststore.",
                    "TLS",
                    -1,
                    ConfigDef.Width.MEDIUM,
                    "TLS Truststore Password",
                ).define(
                    RABBITMQ_TLS_TRUSTSTORE_TYPE,
                    ConfigDef.Type.STRING,
                    "JKS",
                    KEYSTORE_TYPE_VALIDATOR,
                    ConfigDef.Importance.LOW,
                    "Truststore format: 'JKS' or 'PKCS12'.",
                    "TLS",
                    -1,
                    ConfigDef.Width.SHORT,
                    "TLS Truststore Type",
                ).define(
                    RABBITMQ_TLS_KEYSTORE_PATH,
                    ConfigDef.Type.STRING,
                    "",
                    ConfigDef.Importance.MEDIUM,
                    "Path to a keystore holding the client certificate for mutual TLS. Omit to disable mTLS.",
                    "TLS",
                    -1,
                    ConfigDef.Width.MEDIUM,
                    "TLS Keystore Path",
                ).define(
                    RABBITMQ_TLS_KEYSTORE_PASSWORD,
                    ConfigDef.Type.PASSWORD,
                    "",
                    ConfigDef.Importance.MEDIUM,
                    "Password for the keystore used for mutual TLS.",
                    "TLS",
                    -1,
                    ConfigDef.Width.MEDIUM,
                    "TLS Keystore Password",
                ).define(
                    RABBITMQ_TLS_KEYSTORE_TYPE,
                    ConfigDef.Type.STRING,
                    "JKS",
                    KEYSTORE_TYPE_VALIDATOR,
                    ConfigDef.Importance.LOW,
                    "Keystore format: 'JKS' or 'PKCS12'.",
                    "TLS",
                    -1,
                    ConfigDef.Width.SHORT,
                    "TLS Keystore Type",
                ).define(
                    RABBITMQ_MESSAGE_FORMAT,
                    ConfigDef.Type.STRING,
                    "string",
                    MESSAGE_FORMAT_VALIDATOR,
                    ConfigDef.Importance.MEDIUM,
                    "How message bodies are emitted: 'string' (UTF-8 text, STRING_SCHEMA) or 'bytes' (raw bytes, BYTES_SCHEMA).",
                    "Message",
                    -1,
                    ConfigDef.Width.SHORT,
                    "Message Format",
                ).define(
                    RABBITMQ_HEADERS_ENABLED,
                    ConfigDef.Type.BOOLEAN,
                    false,
                    ConfigDef.Importance.LOW,
                    "When true, RabbitMQ application properties are copied to Kafka record headers.",
                    "Message",
                    -1,
                    ConfigDef.Width.SHORT,
                    "Headers Enabled",
                ).define(
                    RABBITMQ_HEADERS_AMQP_ENABLED,
                    ConfigDef.Type.BOOLEAN,
                    false,
                    ConfigDef.Importance.LOW,
                    "When true, standard AMQP message properties (messageId, correlationId, contentType, " +
                        "contentEncoding, to, subject, replyTo, groupId, creationTime) are copied to Kafka record " +
                        "headers prefixed with 'amqp.'.",
                    "Message",
                    -1,
                    ConfigDef.Width.SHORT,
                    "AMQP Headers Enabled",
                ).define(
                    RABBITMQ_MESSAGE_KEY,
                    ConfigDef.Type.STRING,
                    "",
                    ConfigDef.Importance.LOW,
                    "Optional source for the Kafka record key: 'messageId', 'correlationId', or the name of an " +
                        "application property. Empty means no key.",
                    "Message",
                    -1,
                    ConfigDef.Width.MEDIUM,
                    "Message Key Source",
                ).define(
                    RABBITMQ_QUEUE_BUFFER_SIZE,
                    ConfigDef.Type.INT,
                    10000,
                    ConfigDef.Range.atLeast(1),
                    ConfigDef.Importance.MEDIUM,
                    "Capacity of the in-memory buffer between the RabbitMQ consumer thread and Kafka. " +
                        "The consumer thread blocks (backpressure) when full.",
                    "Advanced Settings",
                    -1,
                    ConfigDef.Width.SHORT,
                    "Internal Buffer Size",
                ).define(
                    RABBITMQ_RECOVERY_BACKOFF_SECONDS,
                    ConfigDef.Type.INT,
                    5,
                    ConfigDef.Range.atLeast(1),
                    ConfigDef.Importance.LOW,
                    "Fixed back-off in seconds between RabbitMQ Streams connection recovery attempts.",
                    "Advanced Settings",
                    -1,
                    ConfigDef.Width.SHORT,
                    "Recovery Back-off Seconds",
                ).define(
                    RABBITMQ_POLL_MAX_BATCH_SIZE,
                    ConfigDef.Type.INT,
                    1000,
                    ConfigDef.Range.atLeast(1),
                    ConfigDef.Importance.LOW,
                    "Maximum number of records returned from a single poll() call.",
                    "Advanced Settings",
                    -1,
                    ConfigDef.Width.SHORT,
                    "Poll Max Batch Size",
                )
    }
}
