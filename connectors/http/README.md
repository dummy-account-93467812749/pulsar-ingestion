# HTTP (Netty) Connector for Apache Pulsar

This Pulsar IO connector allows you to ingest data into Apache Pulsar topics by sending HTTP POST requests to an endpoint hosted by the connector. It utilizes Pulsar's built-in Netty source connector, configured to operate in HTTP mode.

## Behavior Change

**Important:** This connector replaces a previous custom HTTP connector that polled an external HTTP endpoint. The new behavior is that this connector **listens** for incoming HTTP POST requests on a configured host and port. The body of the POST request becomes the message payload in Pulsar.

## Configuration

The connector is configured using two main files:

1.  `connector.yaml`: This file defines the Pulsar IO source.
    *   `type`: Should be set to `netty` to use the Netty connector.
    *   `topic`: The Pulsar topic where received HTTP data will be published.
    *   `configFile`: Points to the Netty-specific configuration file (e.g., `config.http.yml`).

2.  `config.http.yml` (or the name specified in `configFile`): This file configures the Netty source connector itself.
    *   `type`: Must be set to `"http"` to enable HTTP server mode.
    *   `host`: The hostname or IP address the HTTP server will listen on (e.g., `"0.0.0.0"` to listen on all interfaces).
    *   `port`: The port number the HTTP server will listen on (e.g., `10999`).
    *   `numberOfThreads` (Optional): The number of threads for the Netty server. Defaults to 1 if not specified.

Refer to the sample `config.http.yml` for structure.

## Usage

1.  Ensure the Pulsar Netty connector NAR file (`pulsar-io-netty-x.y.z.nar`) is installed in your Pulsar `connectors` directory.
2.  Configure `connector.yaml` and `config.http.yml` as described above.
3.  Deploy the connector to your Pulsar cluster.
4.  Send HTTP POST requests to the configured `host:port`. For example, using cURL:
    ```bash
    curl -X POST -d "Your message payload" http://<host>:<port>/
    ```
    The content "Your message payload" will be published to the specified Pulsar topic.

Make sure to replace placeholder values in the configuration files with your desired settings.
