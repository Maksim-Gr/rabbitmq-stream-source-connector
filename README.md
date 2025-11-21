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
  cp build/libs/rabbitmq-source-connector.jar $KAFKA_CONNECT_PLUGINS_DIR/
```

Make sure that `$KAFKA_CONNECT_PLUGINS_DIR/` points to the correct directory where Kafka Connect loads its connectors (this is specified in the Kafka Connect worker's `plugin.path` configuration).

---
### 3. Restart Kafka Connect

# Configuration

| Property                                   | Description                   |
|--------------------------------------------|-------------------------------|
| connector.class                             | Connector class to use, must be `com.github.maksimgr.RabbitSourceConnector` |
| tasks.max                                   | Maximum number of tasks to run |
| rabbitmq.host                               | Hostname of the RabbitMQ broker |
| rabbitmq.port                               | Port of the RabbitMQ broker   |
| rabbitmq.username                           | Username for connecting to RabbitMQ |
| rabbitmq.password                           | Password for connecting to RabbitMQ |
| rabbitmq.virtual.host                       | Virtual host on RabbitMQ to connect to |
| rabbitmq.queue                              | Name of the RabbitMQ queue to consume from |
| rabbitmq.offset                             | Initial offset to consume from, e.g., `first` |
| kafka.topic                                 | Kafka topic where the data should be published |


### Connector config
```json
{
  "name": "RabbitSourceConnector",
  "config": {
    "connector.class": "com.github.maksimgr.RabbitSourceConnector",
    "tasks.max": "1",
    "rabbitmq.host": "localhost",
    "rabbitmq.port": "5672",
    "rabbitmq.username": "guest",
    "rabbitmq.password": "guest",
    "rabbitmq.virtual.host": "/",
    "rabbitmq.queue": "queue_name_here",
    "rabbitmq.offset": "first",
    "kafka.topic": "your_kafka_topic"
  }
}
```