# Docker quickstart

[Back to README](../README.md)

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

## Installation options

## Compatibility

| Component     | Supported                                                   |
|---------------|-------------------------------------------------------------|
| Kafka Connect | 4.x (built against the 4.4.0 Connect API)                   |
| Java          | 17+                                                         |
| RabbitMQ      | 3.9+ with the `rabbitmq_stream` plugin enabled (port `5552`) |

## Install from a release

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

## Build from source

```bash
./gradlew clean build
```

This writes the archive to `build/distributions/` and an unpacked copy to `build/plugin/`, which you can point
`plugin.path` at directly.
