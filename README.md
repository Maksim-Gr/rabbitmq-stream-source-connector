# RabbitMQ Streams source connector

A Kotlin-based Kafka Connect **source** connector that streams messages from
[RabbitMQ Streams](https://www.rabbitmq.com/docs/streams) (the stream protocol on port `5552`, not AMQP)
into Kafka topics, with at-least-once delivery, TLS/mTLS, backpressure and automatic connection recovery.

[![CI](https://github.com/Maksim-Gr/rabbitmq-stream-source-connector/actions/workflows/ci.yaml/badge.svg)](https://github.com/Maksim-Gr/rabbitmq-stream-source-connector/actions/workflows/ci.yaml)
[![Release](https://img.shields.io/github/v/release/Maksim-Gr/rabbitmq-stream-source-connector?sort=semver)](https://github.com/Maksim-Gr/rabbitmq-stream-source-connector/releases)
[![License](https://img.shields.io/github/license/Maksim-Gr/rabbitmq-stream-source-connector)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.21-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)

## Why this connector?

RabbitMQ Streams is a persistent, replayable log that speaks its own binary protocol on port `5552` —
distinct from classic AMQP queues. This connector consumes a stream over that protocol and writes each
message to Kafka, tracking progress in Kafka Connect's own offset store so it resumes exactly where it
left off after a restart or rebalance. Use it to bridge RabbitMQ Streams workloads into a Kafka-based
pipeline without writing custom glue code.

## Quickstart

Spin up RabbitMQ + Kafka + Kafka Connect with the bundled `docker-compose.yml` and stream a message
end-to-end. Requires Docker and a JDK 17.

```bash
# 1. Build the component archive and unpack it into build/plugin, which docker-compose mounts into Connect
./gradlew clean build

# 2. Start RabbitMQ, a Kafka broker and Kafka Connect (Apache Kafka 4.4.0)
docker compose up -d

# 3. Wait until the Connect REST API is ready
until curl -sf http://localhost:8083/ >/dev/null; do sleep 2; done

# 4. Create a RabbitMQ stream and publish a test message into it (via the management HTTP API)
curl -s -u guest:guest -X PUT http://localhost:15672/api/queues/%2f/orders \
  -H 'content-type: application/json' \
  -d '{"durable":true,"arguments":{"x-queue-type":"stream"}}'
curl -s -u guest:guest -X POST http://localhost:15672/api/exchanges/%2f/amq.default/publish \
  -H 'content-type: application/json' \
  -d '{"properties":{},"routing_key":"orders","payload":"{\"id\":1,\"item\":\"book\"}","payload_encoding":"string"}'

# 5. Deploy the connector
curl -s -X POST http://localhost:8083/connectors \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "rabbitmq-source",
    "config": {
      "connector.class": "com.github.maksimgr.RabbitSourceConnector",
      "tasks.max": "1",
      "rabbitmq.host": "rabbitmq",
      "rabbitmq.port": "5552",
      "rabbitmq.username": "guest",
      "rabbitmq.password": "guest",
      "rabbitmq.queue": "orders",
      "rabbitmq.offset": "first",
      "kafka.topic": "rabbitmq.messages"
    }
  }'

# 6. Confirm the connector and its task are RUNNING
curl -s http://localhost:8083/connectors/rabbitmq-source/status

# 7. Read the message back out of Kafka
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:19092 --topic rabbitmq.messages --from-beginning --timeout-ms 10000
```

You should see `{"id":1,"item":"book"}` printed by the console consumer. Tear down with `docker compose down -v`.

## Installation

### Compatibility

| Component     | Supported                                                   |
|---------------|-------------------------------------------------------------|
| Kafka Connect | 4.x (built against the 4.4.0 Connect API)                   |
| Java          | 17+                                                         |
| RabbitMQ      | 3.9+ with the `rabbitmq_stream` plugin enabled (port `5552`) |

### Install from a release

Each [GitHub release](https://github.com/Maksim-Gr/rabbitmq-stream-source-connector/releases) ships a
[Confluent component archive](https://docs.confluent.io/platform/current/connect/confluent-hub/component-archive.html),
`maksimgr-rabbitmq-stream-source-connector-<version>.zip`, plus a `checksums_sha256.txt`. The archive holds the
connector and its dependencies in `lib/`, the license and third-party notices in `doc/`, and an example config in
`etc/`.

Unpack it into a directory listed in the Connect worker's
[`plugin.path`](https://kafka.apache.org/documentation/#connectconfigs_plugin.path) and restart the worker:

```bash
unzip maksimgr-rabbitmq-stream-source-connector-<version>.zip -d /opt/connect-plugins
```

On Confluent Platform you can install the same file with
[`confluent connect plugin install`](https://docs.confluent.io/confluent-cli/current/command-reference/connect/plugin/confluent_connect_plugin_install.html):

```bash
confluent connect plugin install ./maksimgr-rabbitmq-stream-source-connector-<version>.zip
```

The connector ships a ServiceLoader manifest, so it works with every
[`plugin.discovery`](https://kafka.apache.org/documentation/#connectconfigs_plugin.discovery) mode, including
`service_load`.

### Build from source

```bash
./gradlew clean build
```

This writes the archive to `build/distributions/` and an unpacked copy to `build/plugin/`, which you can point
`plugin.path` at directly.

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
| rabbitmq.queue.buffer.size                 | `10000` | Maximum number of records in the in-memory buffer between the RabbitMQ consumer thread and Kafka. See [Backpressure](#backpressure) |
| rabbitmq.queue.buffer.max.bytes            | `67108864` (64 MiB) | Maximum total size of message bodies in that buffer. The buffer is full when either limit is reached. See [Backpressure](#backpressure) |
| rabbitmq.recovery.backoff.seconds          | `5`     | Fixed back-off in seconds between connection recovery attempts |
| rabbitmq.poll.max.batch.size               | `1000`  | Maximum number of records returned from a single `poll()` call |
| rabbitmq.error.handling                    | `fail`  | Action when a message cannot be converted into a Kafka record: `fail` stops the task with an error, `skip` logs the error (stream and offset), drops the message and continues. See [Error handling](#error-handling) |


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

Binary values used as `rabbitmq.message.key` are encoded as Base64. Binary AMQP message IDs and
correlation IDs use the same encoding in `amqp.*` headers, so equal binary values remain stable across replays.

### Multiple queues
Set `rabbitmq.queue` to a comma-separated list to consume from more than one stream. At least one nonblank
stream name is required. Streams are distributed round-robin across tasks up to `tasks.max`:
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

If the offset to resume from has already been removed from the stream (for example by
[retention](https://www.rabbitmq.com/docs/streams#retention) while the connector was down),
RabbitMQ starts from the oldest message still available. The skipped messages are lost, and the
connector logs a WARN naming the stream and the missing offset range:
```
Gap in stream 'orders': expected offset 1200 but received 1500. 300 message(s) are no longer in the stream ...
```
Size stream retention to cover the longest connector downtime you expect.

If the stream instead delivers an offset earlier than the expected one, the connector logs a WARN and
the messages in between are written to Kafka again:
```
Stream 'orders' rewound: expected offset 1500 but received 1200. 300 message(s) will be delivered to Kafka again.
```

> **Upgrading from 0.3.0 or earlier:** older versions named their RabbitMQ consumers
> `kafka-connector-<stream>`, and versions up to 0.2.0 stored offsets on the broker under that name.
> The stream client resumes from such a stored offset in preference to the one committed to Kafka,
> so every restart or reconnection replayed messages consumed since the upgrade. Consumers are now
> unnamed and only Kafka Connect's offset store decides where a task resumes. Offsets left on the broker
> are no longer read and can be ignored.

### Error handling
Kafka Connect's `errors.tolerance` only covers converters and SMTs, not the connector's own code
([docs](https://kafka.apache.org/documentation/#connect_errorreporting)), so the connector has its
own setting for messages it cannot turn into records:

- `rabbitmq.error.handling=fail` (default): the task stops with an error and shows `FAILED` in
  `/connectors/<name>/status`. After a restart it resumes at the same message, so fix or remove
  the cause first.
- `rabbitmq.error.handling=skip`: the error is logged at ERROR with the stream and offset, the
  message is dropped (it is never written to Kafka), and consumption continues with the next one.

Message bodies that are not AMQP binary data but hold a single scalar value (e.g. an AMQP 1.0
`amqp-value` string or number sent by an AMQP 1.0 client) are converted to their string form
rather than treated as errors. Structured bodies (an `amqp-value` map or list, or an
`amqp-sequence`) have no faithful string or byte form, so they are handled as conversion errors
by this setting. A message with no body produces a record with a `null` value.

Use `rabbitmq.message.format=bytes` for binary payloads such as Protobuf or Avro: with `string`,
bytes that are not valid UTF-8 are replaced and cannot be recovered.

### Record timestamp
If the AMQP message carries a `creation-time` property, it is used as the Kafka record
timestamp (`ConsumerRecord.timestamp()` downstream). Messages without a `creation-time`
get no explicit timestamp, so Kafka assigns one on write (broker append time), same as
before this was added.

### Backpressure
The connector uses an internal buffer between the RabbitMQ consumer thread and Kafka. It has two
limits, and it is full as soon as **either** is reached:

- `rabbitmq.queue.buffer.size`: maximum number of records (default 10,000).
- `rabbitmq.queue.buffer.max.bytes`: maximum total size of message bodies (default 64 MiB).

When the buffer is full the consumer thread blocks until space is available — messages are never
dropped. Like Kafka's [`fetch.max.bytes`](https://kafka.apache.org/documentation/#consumerconfigs_fetch.max.bytes),
the byte limit is not absolute: a single message larger than it is still accepted when the buffer is
empty, so consumption never stalls. Sizes are measured on the message body. Keys, headers and JVM object
overhead come on top, and `string` format can take up to twice the body size in memory for non-Latin-1
text, so leave headroom when sizing the worker heap (budget per task, times the number of tasks per worker).

Stopping the task releases a blocked consumer thread within about 100 ms, so shutdown
and rebalances are not held up by a full buffer. The current buffer depth is logged every 30 seconds at INFO level:
```
Internal message queue depth: 1234 / 10000 records, 5242880 / 67108864 bytes
```

### Connection recovery
During reconnection, each stream resumes after the last message buffered or explicitly skipped by the task.
An interrupted delivery is replayed; task restarts still resume from Kafka Connect's committed offsets.

The connector enables automatic reconnection via the RabbitMQ Streams client's built-in recovery mechanism with a fixed back-off between retries, set by `rabbitmq.recovery.backoff.seconds` (default 5 seconds). State transitions are surfaced as log lines:

| Event | Log level |
|-------|-----------|
| Consumer starts recovering after a failure | WARN |
| Consumer recovered successfully | INFO |
| Consumer closed unexpectedly while task is running | ERROR, and the task fails |

If a consumer closes for good (for example the stream was deleted, or recovery gave up), the task
fails on its next `poll()` instead of staying `RUNNING` without consuming anything. Monitor the
task status or enable restarts on your platform so this is noticed.
