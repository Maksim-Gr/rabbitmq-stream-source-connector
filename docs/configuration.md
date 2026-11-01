# Configuration

[Back to README](../README.md)

| Property                                   | Default | Description                   |
|--------------------------------------------|---------|-------------------------------|
| connector.class                            | —       | Must be `com.github.maksimgr.RabbitSourceConnector` |
| tasks.max                                  | —       | Maximum number of tasks; each task handles one or more queues |
| kafka.topic                                | —       | Destination Kafka topic where messages are written |
| rabbitmq.queue                             | —       | Comma-separated list of RabbitMQ stream names to consume from |
| rabbitmq.host                              | `localhost` | Hostname of the RabbitMQ broker |
| rabbitmq.port                              | `5552`  | Port of the RabbitMQ Streams protocol (not the AMQP port 5672) |
| rabbitmq.username                          | —       | Username for connecting to RabbitMQ |
| rabbitmq.password                          | —       | Password for connecting to RabbitMQ |
| rabbitmq.virtual.host                      | `/`     | Virtual host on RabbitMQ to connect to |
| rabbitmq.offset                            | `first` | Starting offset: `first`, `last`, `next`, or a timestamp `dd.MM.yyyy HH:mm:ss` in UTC |
| rabbitmq.requested.heartbeat.seconds       | `60`    | Heartbeat interval in seconds for the RabbitMQ Streams connection |
| rabbitmq.requested.frame.max               | `1048576` | Maximum frame size in bytes for the RabbitMQ Streams connection |
| rabbitmq.tls.enabled                       | `false` | Enable TLS for the RabbitMQ Streams connection |
| rabbitmq.tls.truststore.path               | `""`    | Path to a truststore file. Omit to use the JVM default trust store (for publicly-trusted CAs) |
| rabbitmq.tls.truststore.password           | `""`    | Password for the truststore |
| rabbitmq.tls.truststore.type                | `JKS`   | Truststore format: `JKS` or `PKCS12` |
| rabbitmq.tls.keystore.path                 | `""`    | Path to a keystore holding the client certificate for mutual TLS. Omit to disable mTLS |
| rabbitmq.tls.keystore.password             | `""`    | Password for the keystore used for mutual TLS |
| rabbitmq.tls.keystore.type                  | `JKS`   | Keystore format: `JKS` or `PKCS12` |
| rabbitmq.message.format                    | `string` | How message bodies are emitted: `string` (UTF-8 text, `STRING_SCHEMA`) or `bytes` (raw bytes, `BYTES_SCHEMA`) |
| rabbitmq.headers.enabled                   | `false` | When `true`, RabbitMQ application properties are copied to Kafka record headers |
| rabbitmq.headers.amqp.enabled              | `false` | When `true`, standard AMQP properties (`messageId`, `correlationId`, `contentType`, `contentEncoding`, `to`, `subject`, `replyTo`, `groupId`, `creationTime`) are copied to Kafka record headers prefixed with `amqp.` |
| rabbitmq.message.key                       | `""`    | Source for the Kafka record key: `messageId`, `correlationId`, or an application property name. Empty means no key |
| rabbitmq.queue.buffer.size                 | `10000` | Maximum number of records in the in-memory buffer between the RabbitMQ consumer thread and Kafka. See [Backpressure](operations.md#backpressure) |
| rabbitmq.queue.buffer.max.bytes            | `67108864` (64 MiB) | Maximum total size of message bodies in that buffer. The buffer is full when either limit is reached. See [Backpressure](operations.md#backpressure) |
| rabbitmq.recovery.backoff.seconds          | `5`     | Fixed back-off in seconds between connection recovery attempts |
| rabbitmq.poll.max.batch.size               | `1000`  | Maximum number of records returned from a single `poll()` call |
| rabbitmq.error.handling                    | `fail`  | Action when a message cannot be converted into a Kafka record: `fail` stops the task with an error, `skip` logs the error (stream and offset), drops the message and continues. See [Error handling](operations.md#error-handling) |


## Connector config
```json
{
  "name": "RabbitSourceConnector",
  "config": {
    "connector.class": "com.github.maksimgr.RabbitSourceConnector",
    "tasks.max": "1",
    "rabbitmq.host": "localhost",
    "rabbitmq.port": "5552",
    "rabbitmq.username": "guest",
    "rabbitmq.password": "guest",
    "rabbitmq.virtual.host": "/",
    "rabbitmq.queue": "queue_name_here",
    "rabbitmq.offset": "first",
    "kafka.topic": "your_kafka_topic"
  }
}
```

## TLS config
```json
{
  "name": "RabbitSourceConnector",
  "config": {
    "connector.class": "com.github.maksimgr.RabbitSourceConnector",
    "tasks.max": "1",
    "rabbitmq.host": "localhost",
    "rabbitmq.port": "5551",
    "rabbitmq.username": "guest",
    "rabbitmq.password": "guest",
    "rabbitmq.virtual.host": "/",
    "rabbitmq.queue": "queue_name_here",
    "rabbitmq.offset": "first",
    "kafka.topic": "your_kafka_topic",
    "rabbitmq.tls.enabled": "true",
    "rabbitmq.tls.truststore.path": "/path/to/truststore.jks",
    "rabbitmq.tls.truststore.password": "changeit"
  }
}
```

Binary values used as `rabbitmq.message.key` are encoded as Base64. Binary AMQP message IDs and
correlation IDs use the same encoding in `amqp.*` headers, so equal binary values remain stable across replays.

## Multiple queues
Set `rabbitmq.queue` to a comma-separated list to consume from more than one stream. At least one nonblank
stream name is required. Streams are distributed round-robin across tasks up to `tasks.max`:
```json
{
  "rabbitmq.queue": "stream-a,stream-b,stream-c",
  "tasks.max": "3"
}
```
