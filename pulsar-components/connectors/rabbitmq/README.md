# RabbitMQ Source Connector for Apache Pulsar

This Pulsar IO connector allows you to ingest data from RabbitMQ queues into Apache Pulsar topics. It acts as a RabbitMQ consumer and publishes the consumed messages to Pulsar.

## Configuration

The connector is configured using two main files:

1.  `connector.yaml`: This file defines the Pulsar IO source.
    *   `name`: A descriptive name for the connector instance (e.g., `rabbitmq-source`).
    *   `type`: Should be set to `rabbitmq` to use Pulsar's built-in RabbitMQ connector. (Note: If a specific `className` is required, that should be used instead of or in addition to `type`).
    *   `topic`: The target Pulsar topic where messages from RabbitMQ will be published (e.g., `persistent://public/default/rabbitmq-input-topic`).
    *   `configFile`: Points to the RabbitMQ-specific configuration file (e.g., `config.sample.yml` or a renamed `config.rabbitmq.yml`).
    *   `secretKeys`: Lists any configuration keys that contain sensitive information, such as `RABBITMQ_USERNAME` and `RABBITMQ_PASSWORD`.
    *   `parallelism`: The number of instances of this connector to run.

2.  `config.sample.yml` (or the file specified in `configFile`): This file configures the RabbitMQ source connector itself. Key parameters include:
    *   `connectionName`: A name for the AMQP connection.
    *   `host`: The hostname or IP address of the RabbitMQ server.
    *   `port`: The port number of the RabbitMQ server (typically `5672` for non-TLS AMQP).
    *   `virtualHost`: The virtual host to connect to on the RabbitMQ server.
    *   `username`: The username for RabbitMQ authentication. This is typically injected via the `secretKeys` mechanism.
    *   `password`: The password for RabbitMQ authentication. This is also typically injected via `secretKeys`.
    *   `queueName`: The name of the RabbitMQ queue to consume messages from.
    *   `prefetchCount` (Optional): The maximum number of messages that the server will deliver, unacknowledged, at a time.

Refer to the provided `config.sample.yml` for a base structure and the official Pulsar RabbitMQ connector documentation for all possible parameters.

## Setup

1.  Ensure the Pulsar RabbitMQ connector NAR file (e.g., `pulsar-io-rabbitmq.nar`) is installed in your Pulsar `connectors` directory.
2.  Configure `connector.yaml` and your RabbitMQ-specific config file (`config.sample.yml` or similar) with your RabbitMQ server details and credentials.
3.  Ensure RabbitMQ credentials (`username`, `password`) are properly set up in Pulsar's secrets management and referenced in `connector.yaml` under `secretKeys`.
4.  Deploy the connector to your Pulsar cluster.

Messages consumed from the specified RabbitMQ queue will be published to the configured Pulsar topic.
