# RabbitMQ Streams source connector

Stream messages from [RabbitMQ Streams](https://www.rabbitmq.com/docs/streams) into Kafka
using Kafka Connect. Supports at-least-once delivery, TLS/mTLS, multiple streams,
backpressure, and automatic connection recovery.

[![CI](https://github.com/Maksim-Gr/rabbitmq-stream-source-connector/actions/workflows/ci.yaml/badge.svg)](https://github.com/Maksim-Gr/rabbitmq-stream-source-connector/actions/workflows/ci.yaml)
[![Release](https://img.shields.io/github/v/release/Maksim-Gr/rabbitmq-stream-source-connector?sort=semver)](https://github.com/Maksim-Gr/rabbitmq-stream-source-connector/releases)
[![License](https://img.shields.io/github/license/Maksim-Gr/rabbitmq-stream-source-connector)](LICENSE)

## Install

Requires **Java 17+**, **Kafka Connect 4.x**, and **RabbitMQ 3.9+** with the
`rabbitmq_stream` plugin enabled. The connector uses the Streams protocol on port
`5552`.

Download `maksimgr-rabbitmq-stream-source-connector-<version>.zip` from
[GitHub Releases](https://github.com/Maksim-Gr/rabbitmq-stream-source-connector/releases),
unpack it into a directory listed in your Connect worker's `plugin.path`, and restart the worker:

```bash
unzip maksimgr-rabbitmq-stream-source-connector-<version>.zip -d /opt/connect-plugins
```

See [installation options](docs/quickstart.md#installation-options) for compatibility
details and Confluent CLI installation.

## Configure

Create a connector through the Kafka Connect REST API, replacing the host,
credentials, stream name, and Kafka topic with your own:

```bash
curl -X POST http://localhost:8083/connectors \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "rabbitmq-source",
    "config": {
      "connector.class": "com.github.maksimgr.RabbitSourceConnector",
      "tasks.max": "1",
      "rabbitmq.host": "localhost",
      "rabbitmq.username": "guest",
      "rabbitmq.password": "guest",
      "rabbitmq.queue": "orders",
      "kafka.topic": "rabbitmq.messages"
    }
  }'
```

The RabbitMQ host must be reachable from the **Connect worker**. The stream must
already exist. For multiple streams, set `rabbitmq.queue` to a comma-separated list.

On first use, consumption starts at the beginning of the stream. After a restart,
saved Kafka Connect offsets take precedence over `rabbitmq.offset`. Delivery is
**at least once**, so duplicates can occur after a crash or rebalance.

See the [configuration reference](docs/configuration.md) for all settings and TLS
examples. Sample files are available in [JSON](examples/json/connector-config.json)
and [properties](examples/connector.properties) formats.

## Try it locally

Follow the [Docker quickstart](docs/quickstart.md) to run RabbitMQ, Kafka, and Kafka
Connect, then publish and consume a test message.

## Documentation

- [Configuration](docs/configuration.md): all settings, TLS, and multiple streams.
- [Operations](docs/operations.md): offsets, retention gaps, error handling,
  timestamps, backpressure, and connection recovery.

## Build from source

```bash
./gradlew clean build
```

The component archive is written to `build/distributions/`, with an unpacked copy
in `build/plugin/` for local use.
