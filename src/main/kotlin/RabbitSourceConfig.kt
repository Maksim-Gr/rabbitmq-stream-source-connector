import org.apache.kafka.common.config.AbstractConfig
import org.apache.kafka.common.config.ConfigDef

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
        private const val RABBITMQ_REQUESTED_FRAME_MAX = "rabbitmq.requested.frame.max"
        private const val RABBITMQ_HANDSHAKE_TIMEOUT = "rabbitmq.handshake.timeout.ms"
        private const val RABBITMQ_REQUESTED_HEARTBEAT = "rabbitmq.requested.heartbeat.seconds"
        private const val CONFIG_NAME_RABBITMQ_OFFSET = "rabbitmq.offset"

        val CONFIG: ConfigDef =
            ConfigDef()
                .define(
                    // name =
                    CONFIG_NAME_DESTINATION_KAFKA_TOPIC,
                    // type =
                    ConfigDef.Type.STRING,
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
                    ConfigDef.Type.STRING,
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
                    RABBITMQ_HANDSHAKE_TIMEOUT,
                    // type =
                    ConfigDef.Type.INT,
                    // defaultValue =
                    10000,
                    // importance =
                    ConfigDef.Importance.LOW,
                    // documentation =
                    "The AMQP 0-9-1 handshake timeout in milliseconds (default 10 seconds).",
                    // group =
                    "Advanced Settings",
                    // orderInGroup =
                    -1,
                    // width =
                    ConfigDef.Width.SHORT,
                    // displayName =
                    "Handshake Timeout (ms)",

                ).define(
                    // name =
                    CONFIG_NAME_RABBITMQ_OFFSET,
                    // type =
                    ConfigDef.Type.STRING,
                    // defaultValue =
                    "first",
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
                    "Offest position",
                )
    }
}