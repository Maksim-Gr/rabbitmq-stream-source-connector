package com.github.maksimgr

import com.rabbitmq.stream.OffsetSpecification
import org.apache.kafka.common.config.AbstractConfig
import org.apache.kafka.common.config.ConfigDef
import org.apache.kafka.common.config.ConfigException
import org.apache.kafka.common.config.types.Password
import java.time.Duration

enum class MessageFormat { STRING, BYTES }

enum class ErrorHandling { FAIL, SKIP }

enum class StoreType { JKS, PKCS12 }

/** A truststore or keystore file. [password] is Kafka's [Password], so it never appears in `toString()`. */
class StoreSettings(
    val path: String,
    val password: Password,
    val type: StoreType,
)

/** TLS settings; a null store means it is not configured (default trust, or no client certificate). */
class TlsSettings(
    val truststore: StoreSettings?,
    val keystore: StoreSettings?,
)

class RabbitSourceConfig(
    props: Map<*, *>,
) : AbstractConfig(CONFIG, props) {
    val topic: String = getString(CONFIG_NAME_DESTINATION_KAFKA_TOPIC)

    /** Configured stream names, trimmed, without empty entries or duplicates. */
    val streams: List<String> = parseStreams(getList(CONFIG_NAME_SOURCE_RABBITMQ_QUEUES))

    val host: String = getString(RABBITMQ_HOST)
    val port: Int = getInt(RABBITMQ_PORT)
    val username: String = getString(RABBITMQ_USERNAME)
    val password: Password = getPassword(RABBITMQ_PASSWORD)
    val virtualHost: String = getString(RABBITMQ_VIRTUAL_HOST)
    val requestedFrameMax: Int = getInt(RABBITMQ_REQUESTED_FRAME_MAX)
    val requestedHeartbeat: Duration = Duration.ofSeconds(getInt(RABBITMQ_REQUESTED_HEARTBEAT).toLong())
    val recoveryBackoff: Duration = Duration.ofSeconds(getInt(RABBITMQ_RECOVERY_BACKOFF_SECONDS).toLong())

    /** Null when TLS is disabled. */
    val tls: TlsSettings? =
        if (getBoolean(RABBITMQ_TLS_ENABLED)) {
            TlsSettings(
                truststore = store(RABBITMQ_TLS_TRUSTSTORE_PATH, RABBITMQ_TLS_TRUSTSTORE_PASSWORD, RABBITMQ_TLS_TRUSTSTORE_TYPE),
                keystore = store(RABBITMQ_TLS_KEYSTORE_PATH, RABBITMQ_TLS_KEYSTORE_PASSWORD, RABBITMQ_TLS_KEYSTORE_TYPE),
            )
        } else {
            null
        }

    /** The configured offset as written, for logging. */
    val configuredOffset: String = getString(CONFIG_NAME_RABBITMQ_OFFSET)
    val startOffset: OffsetSpecification = RabbitOffsetResolver.resolveOffset(configuredOffset)

    val bufferMaxRecords: Int = getInt(RABBITMQ_QUEUE_BUFFER_SIZE)
    val bufferMaxBytes: Long = getLong(RABBITMQ_QUEUE_BUFFER_MAX_BYTES)
    val pollMaxBatchSize: Int = getInt(RABBITMQ_POLL_MAX_BATCH_SIZE)

    val messageFormat: MessageFormat = choice(RABBITMQ_MESSAGE_FORMAT)
    val headersEnabled: Boolean = getBoolean(RABBITMQ_HEADERS_ENABLED)
    val amqpHeadersEnabled: Boolean = getBoolean(RABBITMQ_HEADERS_AMQP_ENABLED)

    /** 'messageId', 'correlationId', an application property name, or null for no key. */
    val messageKeySource: String? = getString(RABBITMQ_MESSAGE_KEY).trim().ifEmpty { null }
    val errorHandling: ErrorHandling = choice(RABBITMQ_ERROR_HANDLING)

    private fun store(
        pathKey: String,
        passwordKey: String,
        typeKey: String,
    ): StoreSettings? {
        val path = getString(pathKey)
        if (path.isEmpty()) return null
        return StoreSettings(path, getPassword(passwordKey), choice(typeKey))
    }

    private inline fun <reified E : Enum<E>> choice(key: String): E =
        parseChoice<E>(getString(key)) ?: error("'$key' passed validation but is not a ${E::class.simpleName}")

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
        private const val RABBITMQ_QUEUE_BUFFER_MAX_BYTES = "rabbitmq.queue.buffer.max.bytes"
        private const val DEFAULT_QUEUE_BUFFER_MAX_BYTES = 64L * 1024 * 1024
        private const val RABBITMQ_RECOVERY_BACKOFF_SECONDS = "rabbitmq.recovery.backoff.seconds"
        private const val RABBITMQ_POLL_MAX_BATCH_SIZE = "rabbitmq.poll.max.batch.size"
        private const val RABBITMQ_ERROR_HANDLING = "rabbitmq.error.handling"

        /** Returns a copy of [props] that assigns only [streams] to a task. */
        fun forStreams(
            props: Map<String, String>,
            streams: List<String>,
        ): Map<String, String> = HashMap(props).apply { put(CONFIG_NAME_SOURCE_RABBITMQ_QUEUES, streams.joinToString(",")) }

        private fun parseStreams(values: List<String>): List<String> = values.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        private inline fun <reified E : Enum<E>> parseChoice(value: String): E? =
            enumValues<E>().firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }

        /** Accepts the names of [E], ignoring case and surrounding whitespace. */
        private inline fun <reified E : Enum<E>> choiceValidator(crossinline display: (E) -> String) =
            ConfigDef.Validator { name, value ->
                if (value is String && parseChoice<E>(value) == null) {
                    val allowed = enumValues<E>().joinToString(" or ") { "'${display(it)}'" }
                    throw ConfigException(name, value, "Must be $allowed")
                }
            }

        private val MESSAGE_FORMAT_VALIDATOR = choiceValidator<MessageFormat> { it.name.lowercase() }

        private val ERROR_HANDLING_VALIDATOR = choiceValidator<ErrorHandling> { it.name.lowercase() }

        private val NON_EMPTY_STRING_VALIDATOR =
            ConfigDef.Validator { name, value ->
                if (value is String && value.trim().isEmpty()) {
                    throw ConfigException(name, value, "Value must not be empty")
                }
            }

        private val PORT_RANGE_VALIDATOR = ConfigDef.Range.between(1, 65535)

        private val KEYSTORE_TYPE_VALIDATOR = choiceValidator<StoreType> { it.name }

        private val OFFSET_VALIDATOR =
            ConfigDef.Validator { name, value ->
                if (value is String) {
                    try {
                        RabbitOffsetResolver.resolveOffset(value)
                    } catch (e: IllegalArgumentException) {
                        throw ConfigException(name, value, e.message).apply { initCause(e) }
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
                    "Maximum number of records held in the in-memory buffer between the RabbitMQ consumer thread " +
                        "and Kafka. The buffer is full when either this or '$RABBITMQ_QUEUE_BUFFER_MAX_BYTES' is " +
                        "reached, and the consumer thread then blocks (backpressure).",
                    "Advanced Settings",
                    -1,
                    ConfigDef.Width.SHORT,
                    "Internal Buffer Size",
                ).define(
                    RABBITMQ_QUEUE_BUFFER_MAX_BYTES,
                    ConfigDef.Type.LONG,
                    DEFAULT_QUEUE_BUFFER_MAX_BYTES,
                    ConfigDef.Range.atLeast(1),
                    ConfigDef.Importance.MEDIUM,
                    "Maximum total size in bytes of message bodies held in the in-memory buffer. The buffer is " +
                        "full when either this or '$RABBITMQ_QUEUE_BUFFER_SIZE' is reached, and the consumer thread " +
                        "then blocks (backpressure). Not an absolute maximum: a message larger than this is still " +
                        "accepted when the buffer is empty, so consumption always makes progress.",
                    "Advanced Settings",
                    -1,
                    ConfigDef.Width.SHORT,
                    "Internal Buffer Max Bytes",
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
                ).define(
                    RABBITMQ_ERROR_HANDLING,
                    ConfigDef.Type.STRING,
                    "fail",
                    ERROR_HANDLING_VALIDATOR,
                    ConfigDef.Importance.MEDIUM,
                    "Action taken when a RabbitMQ message cannot be converted into a Kafka record. " +
                        "'fail' (default): stop the task with an error; it stays FAILED and, after a restart, " +
                        "fails again on the same message until the cause is fixed. " +
                        "'skip': log the error with the stream name and offset, drop the message (it is not " +
                        "written to Kafka) and continue with the next one.",
                    "Message",
                    -1,
                    ConfigDef.Width.SHORT,
                    "Error Handling",
                )
    }
}
