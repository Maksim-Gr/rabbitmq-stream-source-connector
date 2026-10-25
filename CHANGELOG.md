# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed
- Structured message bodies (an AMQP 1.0 `amqp-value` map or list, or an `amqp-sequence`) are now handled by
  `rabbitmq.error.handling` instead of being written to Kafka as Java debug text such as `{orderId=7}`.
- Empty entries in `rabbitmq.queue` (for example `orders,,events` or a trailing comma) are no longer assigned to a
  task as a stream name. Previously a task that received only an empty entry failed to start.

## [0.4.0] - 2026-10-19

### Added
- Kafka record timestamp is now set from the RabbitMQ message's AMQP `creation-time` property, when present.
- `rabbitmq.headers.amqp.enabled` config to copy standard AMQP message properties (`messageId`, `correlationId`,
  `contentType`, `contentEncoding`, `to`, `subject`, `replyTo`, `groupId`, `creationTime`) onto Kafka record headers,
  prefixed with `amqp.`.
- PKCS12 keystore/truststore support via `rabbitmq.tls.truststore.type` / `rabbitmq.tls.keystore.type` (default `JKS`).
- JaCoCo test coverage reporting and detekt static analysis, wired into the build and CI.
- `rabbitmq.error.handling` config (`fail`/`skip`, default `fail`): with `skip`, messages that cannot be converted
  into a Kafka record are logged with their stream and offset and dropped instead of failing the task.
- A WARN is logged when stream offsets jump past the expected offset (for example after retention removed messages
  the connector had not yet delivered), naming the stream and the number of missing messages.
- `rabbitmq.queue.buffer.max.bytes` config (default 64 MiB) caps the total message-body size held in the internal
  buffer, alongside the record cap `rabbitmq.queue.buffer.size`; the buffer is full when either is reached. As with
  Kafka's `fetch.max.bytes`, a single larger message is still accepted when the buffer is empty. The periodic
  buffer-depth log line now includes bytes.
- A WARN is logged when stream offsets jump back behind the expected offset, naming the stream and the number of
  messages that will be redelivered.

### Fixed
- Tasks no longer resume from a stale offset stored on the broker. Consumers were still named
  `kafka-connector-<stream>`, and the RabbitMQ stream client looks up an offset stored under a consumer's name on
  every subscription and reconnection, even with tracking disabled. It then uses that offset instead of the one
  committed to Kafka. Versions <= 0.2.0 stored offsets under that name, so after an upgrade every restart,
  rebalance or reconnection replayed everything consumed since then. Consumers are now unnamed.
- With `rabbitmq.error.handling=fail`, no record after the failing message reaches Kafka any more. Previously a
  later record could be committed, so the restart skipped the failed message.
- An interrupted message handler now fails the task instead of silently dropping the message it was handling.
- Binary (`byte[]`) application properties are copied as byte headers instead of identity strings like `[B@1b6d3586`.
- The task now fails when a RabbitMQ consumer closes unexpectedly, instead of staying `RUNNING` without consuming.
- Message bodies that are not AMQP binary data (e.g. AMQP 1.0 `amqp-value`) are converted to their string form
  instead of failing the task; messages without a body produce a `null` value.
- Stopping the task no longer hangs when the internal buffer is full and the consumer thread is blocked on it.
- A task that fails during `start()` now also closes any consumers it had already created.

### Changed
- Upgraded `stream-client` from 1.8.0 to 1.11.0, `slf4j-api` from 2.0.18 to 2.0.20, and the targeted Kafka Connect
  API from 4.3.0 to 4.3.1.

## [0.3.0] - 2026-06-25

### Added
- LICENSE, CONTRIBUTING and SECURITY docs, README badges, and a working docker-compose quickstart.

### Fixed
- Offset-resume Integer/Long coercion bug when resuming from Kafka Connect's offset store.
- Task now fails fast on message-handler errors instead of silently dropping records.
- Removed redundant RabbitMQ-side offset tracking in favor of Kafka Connect's offset store as the single source of truth.

## [0.2.0] - 2026-06-12

### Fixed
- Read `rabbitmq.password` via `getPassword()` instead of `getString()`.
- Hardened task lifecycle and connector edge cases; various correctness and config-driven robustness fixes.

### Changed
- CI: allow JReleaser to overwrite existing releases.

### Docs
- README updated with missing config properties and an Operations section.

## [0.1.2] - 2026-04-26

### Added
- Automatic connection recovery with backoff; consumer lifecycle state transitions are logged.
- Internal message queue depth logged every 30 seconds.

### Fixed
- Apply backpressure instead of dropping messages when the internal buffer is full.

### Changed
- Upgraded `stream-client` from 0.16.0 to 1.6.0.

## [0.1.1] - 2026-03-08

### Fixed
- `start()`/`stop()` now use a try/catch block and drain safely, removing blocking behavior on shutdown.

## [0.1.0] - 2026-03-08

### Added
- Initial release of the RabbitMQ Stream Source Connector for Kafka Connect.
