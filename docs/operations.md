# Operations

[Back to README](../README.md)

## Offsets and delivery semantics
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

## Error handling
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

## Record timestamp
If the AMQP message carries a `creation-time` property, it is used as the Kafka record
timestamp (`ConsumerRecord.timestamp()` downstream). Messages without a `creation-time`
get no explicit timestamp, so Kafka assigns one on write (broker append time), same as
before this was added.

## Backpressure
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

## Connection recovery
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
