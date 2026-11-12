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

Set `rabbitmq.gap.handling=fail` when a missing position must stop the pipeline. The task fails before
buffering the message after the gap and leaves its resume position pending; a restart still uses the last
committed offset. This policy also applies when `rabbitmq.error.handling=skip`: conversion error handling
cannot bypass it. The default `warn` preserves the existing behavior. A first subscription with no committed
offset has no expected position until its first delivery, so it cannot detect messages removed before that
delivery. Rewinds continue to warn and replay under either policy.

After a gap failure, investigate retention and recover missing data from an upstream archive or another
source. Restarting alone cannot restore expired messages. If you explicitly accept the gap, change the
policy to `warn`, resume, and record the accepted missing range before restoring `fail`.

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

For clustered deployments, configure [multiple bootstrap endpoints](configuration.md#cluster-bootstrap-endpoints).
Replicated streams and reachable advertised addresses are required for recovery after losing a leader.

## Metrics and alerts

On Kafka Connect 4.1+, the connector registers numeric metrics through the worker's plugin metrics registry.
They are exposed by the worker's JMX reporter under the following object names (property order may vary):

```text
kafka.connect:type=plugins,connector=<name>,task=<id>,component=rabbitmq-stream
kafka.connect:type=plugins,connector=<name>,task=<id>,component=rabbitmq-stream,stream=<stream>
```

Connect 4.0 continues to operate and logs a warning that these connector metrics are unavailable.
Metrics reset whenever a task starts and are removed when it stops. Stream labels are limited to streams
assigned to that task. Names and values describe RabbitMQ delivery into the buffer, **not Kafka acknowledgements**.

| Scope | Attribute | Meaning |
|-------|-----------|---------|
| Task | `buffer-records`, `buffer-bytes` | Current buffered record count and message body bytes |
| Task | `buffer-record-capacity`, `buffer-byte-capacity` | Configured buffer limits; the byte limit permits one oversized body |
| Stream | `received-total` | Delivery attempts observed, including conversion failures and replays |
| Stream | `buffered-total` | Records accepted into the buffer |
| Stream | `last-buffered-offset` | Most recently buffered offset, or `-1` before the first buffered record; may decrease on rewind |
| Stream | `last-received-time-ms` | Wall-clock time of the last delivery attempt, or `-1` before any attempt |
| Stream | `skipped-total` | Messages discarded by conversion error handling |
| Stream | `gaps-total`, `missing-offsets-total` | Forward jumps and the total number of skipped offset positions |
| Stream | `rewinds-total` | Backward jumps |
| Stream | `recoveries-total`, `recovering` | Recovery attempts and current state (`1` recovering, `0` otherwise) |
| Stream | `recovery-seconds-total` | Time spent recovering, including the current recovery |
| Stream | `blocked-seconds-total` | Time waiting on a full buffer, including the current wait |

The timestamp detects a quiet input, not a Kafka delivery stall; alert on it only for streams with expected
traffic. Missing offset positions are an indication of a continuity gap, not a measurement of current source lag.
Use Connect's standard source-record write/active metrics and offset-commit failure metrics alongside these
attributes to detect Kafka-side stalls. Independently monitor worker availability and the Connect task-status
REST endpoint: failed or absent tasks may have no connector metrics left to scrape.

Example [JMX exporter rules](../examples/monitoring/jmx-exporter.yaml) map these attributes to Prometheus names;
merge them into your worker's existing exporter configuration. Example [Prometheus alerts](../examples/monitoring/alerts.yaml)
cover gaps, skipped messages, sustained recovery, buffer saturation, and quiet inputs. Adjust thresholds to your
recovery objectives and expected traffic, and route the alerts through your existing monitoring setup.

## Recovery verification

The Docker integration suite runs the packaged plugin in two distributed Connect workers with Kafka's real
offset store and a three-node RabbitMQ cluster. It verifies complete delivery of publisher-confirmed message
IDs through simultaneous worker crashes, reassignment to a surviving worker and subsequent rejoining, a Kafka
write outage, stream-leader loss, and startup with an unavailable bootstrap endpoint. Tests report observed
duplicate counts; at-least-once delivery permits duplicates. Run it locally with `./gradlew integrationTest`.
The CI workflow runs integration tests for pull requests, avoiding a second run on the subsequent push to `main`.
