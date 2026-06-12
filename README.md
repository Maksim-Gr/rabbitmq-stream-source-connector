# RabbitMQ streams source connector
A Kotlin-based Kafka Connect Source Connector for RabbitMQ Streams.

## Installation
Follow these steps to install and deploy the RabbitMQ Source Connector:
### 1. Build the Connector
First, build the connector JAR file using Gradle:
```bash
  ./gradlew clean build
```
This command will clean the project and build the JAR file. The resulting JAR will be located in the `build/libs/` directory.

---

### 2. Copy the JAR to Kafka Connect Plugin Directory
After building the JAR, copy it into your Kafka Connect plugin path. You can do so by running the following command:

```bash
  cp build/libs/rabbitmq-stream-source-connector-*.jar $KAFKA_CONNECT_PLUGINS_DIR/
```

Make sure that `$KAFKA_CONNECT_PLUGINS_DIR/` points to the correct directory where Kafka Connect loads its connectors (this is specified in the Kafka Connect worker's `plugin.path` configuration).

---
### 3. Restart Kafka Connect

# Configuration

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
| rabbitmq.offset                            | `first` | Starting offset: `first`, `last`, `next`, or a timestamp `dd.MM.yyyy HH:mm:ss` |
| rabbitmq.requested.heartbeat.seconds       | `60`    | Heartbeat interval in seconds for the RabbitMQ Streams connection |
| rabbitmq.requested.frame.max               | `1048576` | Maximum frame size in bytes for the RabbitMQ Streams connection |
| rabbitmq.tls.enabled                       | `false` | Enable TLS for the RabbitMQ Streams connection |
| rabbitmq.tls.truststore.path               | `""`    | Path to a JKS truststore file. Omit to use the JVM default trust store (for publicly-trusted CAs) |
| rabbitmq.tls.truststore.password           | `""`    | Password for the JKS truststore |
| rabbitmq.tls.keystore.path                 | `""`    | Path to a JKS keystore holding the client certificate for mutual TLS. Omit to disable mTLS |
| rabbitmq.tls.keystore.password             | `""`    | Password for the JKS keystore used for mutual TLS |
| rabbitmq.message.format                    | `string` | How message bodies are emitted: `string` (UTF-8 text, `STRING_SCHEMA`) or `bytes` (raw bytes, `BYTES_SCHEMA`) |
| rabbitmq.headers.enabled                   | `false` | When `true`, RabbitMQ application properties are copied to Kafka record headers |
| rabbitmq.message.key                       | `""`    | Source for the Kafka record key: `messageId`, `correlationId`, or an application property name. Empty means no key |
| rabbitmq.queue.buffer.size                 | `10000` | Capacity of the in-memory buffer between the RabbitMQ consumer thread and Kafka |
| rabbitmq.recovery.backoff.seconds          | `5`     | Fixed back-off in seconds between connection recovery attempts |
| rabbitmq.poll.max.batch.size               | `1000`  | Maximum number of records returned from a single `poll()` call |


### Connector config
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

### TLS config
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

### Multiple queues
Set `rabbitmq.queue` to a comma-separated list to consume from more than one stream. Queues are distributed round-robin across tasks up to `tasks.max`:
```json
{
  "rabbitmq.queue": "stream-a,stream-b,stream-c",
  "tasks.max": "3"
}
```

# Operations

### Offsets and delivery semantics
On startup each task first checks Kafka Connect's offset store (via the
`OffsetStorageReader`) for the last committed offset of every stream it owns. If one
exists, the task resumes from the next offset; otherwise it starts from the configured
`rabbitmq.offset`. Because the offset committed to Kafka is the source of truth, the
connector provides **at-least-once** delivery — after a crash or rebalance, records
that were buffered but not yet committed to Kafka may be redelivered (duplicates).
Make downstream consumers idempotent if exactly-once is required.

### Backpressure
The connector uses an internal buffer (default 10,000 records, see
`rabbitmq.queue.buffer.size`) between the RabbitMQ consumer thread and Kafka. When the
buffer is full the consumer thread blocks until space is available — messages are never
dropped. The current buffer depth is logged every 30 seconds at INFO level:
```
Internal message queue depth: 1234 / 10000
```

### Connection recovery
The connector enables automatic reconnection via the RabbitMQ Streams client's built-in recovery mechanism with a fixed 5-second back-off between retries. State transitions are surfaced as log lines:

| Event | Log level |
|-------|-----------|
| Consumer starts recovering after a failure | WARN |
| Consumer recovered successfully | INFO |
| Consumer closed unexpectedly while task is running | ERROR |
