# Kafka Source Connector for Apache Pulsar

This Pulsar IO connector allows you to ingest data from Apache Kafka topics into Apache Pulsar topics. It acts as a Kafka consumer and publishes the consumed messages to Pulsar.

## Configuration

The connector is configured using two main files:

1.  `connector.yaml`: This file defines the Pulsar IO source.
    *   `name`: A descriptive name for the connector instance (e.g., `kafka-source`).
    *   `type`: Should be set to `kafka` to use Pulsar's built-in Kafka connector. (Note: If a specific `className` is required, that should be used instead of or in addition to `type`).
    *   `topic`: The target Pulsar topic where messages from Kafka will be published (e.g., `persistent://public/default/kafka-input-topic`).
    *   `configFile`: Points to the Kafka-specific configuration file (e.g., `config.sample.yml` or a renamed `config.kafka.yml`).
    *   `secretKeys`: Lists any configuration keys that contain sensitive information (e.g., `KAFKA_BROKERS_SASL_JAAS_CONFIG`) which should be managed as secrets.
    *   `parallelism`: The number of instances of this connector to run.

2.  `config.sample.yml` (or the file specified in `configFile`): This file configures the Kafka source connector itself. Key parameters include:
    *   `bootstrapServers`: A comma-separated list of Kafka broker addresses (e.g., `your-kafka-broker1:9092,your-kafka-broker2:9092`).
    *   `groupId`: The Kafka consumer group ID that this connector instance will belong to.
    *   `topic`: The specific Kafka topic from which messages should be consumed. This is the *source* Kafka topic.
    *   `fetchMessageMaxBytes`: Maximum message size the connector will fetch.
    *   `autoCommitEnabled`: Whether the Kafka consumer should auto-commit offsets.
    *   Authentication parameters (if required by your Kafka cluster), such as:
        *   `security.protocol` (e.g., `SASL_SSL`)
        *   `sasl.mechanism` (e.g., `PLAIN`, `SCRAM-SHA-256`, `GSSAPI`)
        *   `sasl.jaas.config`: The JAAS configuration for SASL authentication. Often provided via the `secretKeys` mechanism in `connector.yaml`.

Refer to the provided `config.sample.yml` for a base structure and the official Pulsar Kafka connector documentation for all possible parameters.

## Setup

1.  Ensure the Pulsar Kafka connector NAR file (e.g., `pulsar-io-kafka.nar`) is installed in your Pulsar `connectors` directory.
2.  Configure `connector.yaml` and your Kafka-specific config file (`config.sample.yml` or similar) with your Kafka cluster details and desired Pulsar topic.
3.  If using SASL authentication, ensure any secrets (like `sasl.jaas.config`) are properly set up in Pulsar's secrets management and referenced in `connector.yaml`.
4.  Deploy the connector to your Pulsar cluster.

Messages consumed from the specified Kafka topic will be published to the configured Pulsar topic.
