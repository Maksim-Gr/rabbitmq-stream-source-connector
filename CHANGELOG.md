# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

### Fixed
- The task now fails when a RabbitMQ consumer closes unexpectedly, instead of staying `RUNNING` without consuming.
- Message bodies that are not AMQP binary data (e.g. AMQP 1.0 `amqp-value`) are converted to their string form
  instead of failing the task; messages without a body produce a `null` value.
- Stopping the task no longer hangs when the internal buffer is full and the consumer thread is blocked on it.
- A task that fails during `start()` now also closes any consumers it had already created.

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
