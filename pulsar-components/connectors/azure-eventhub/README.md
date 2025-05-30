# Azure Event Hubs AMQP Connector for Apache Pulsar

This Pulsar IO connector allows you to ingest data from Azure Event Hubs into Apache Pulsar topics using the AMQP 1.0 protocol. Azure Event Hubs provides an AMQP 1.0 compatible endpoint, enabling robust and scalable data streaming.

## Configuration

The connector is configured using a YAML file, typically named `config.amqp.yml`, which is specified in the main `connector.yaml` file.

Refer to `config.amqp.yml` for a sample configuration structure. Key parameters include:

*   **Connection Details:**
    *   `connection.host`: Your Azure Event Hubs namespace FQDN (e.g., `your-namespace.servicebus.windows.net`).
    *   `connection.port`: Typically `5671` for AMQP over TLS.
    *   `connection.tlsEnabled`: Should be `true` for Azure Event Hubs.
*   **Authentication:**
    *   `authentication.saslMechanism`: Commonly `PLAIN`.
    *   `authentication.username`: Your Event Hubs SAS policy name (e.g., `$ConnectionString` or a specific policy name like `RootManageSharedAccessKey`).
    *   `authentication.password`: The connection string or the primary/secondary key of your SAS policy.
*   **Source Details:**
    *   `source.address`: The name of your specific Azure Event Hub instance.
    *   `source.consumerGroupId`: (Optional) The consumer group to use. Defaults to `$Default` if not specified or handled by Event Hubs.

## Setup

1.  Ensure the Pulsar AMQP connector NAR file (e.g., `pulsar-io-amqp.nar` or a specific one for Event Hubs if available) is installed in your Pulsar `connectors` directory.
2.  Configure the `connector.yaml` to use `type: amqp` (or the specific type/class for the AMQP connector) and point to your `config.amqp.yml`.
3.  Deploy the connector to your Pulsar cluster.

Make sure to replace placeholder values in `config.amqp.yml` with your actual Azure Event Hubs details.
