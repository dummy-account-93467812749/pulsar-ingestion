# AWS Kinesis Source Connector for Apache Pulsar

This Pulsar IO connector allows you to ingest data from AWS Kinesis streams into Apache Pulsar topics. It acts as a Kinesis consumer and publishes the records fetched from Kinesis to Pulsar.

## Configuration

The connector is configured using two main files:

1.  `connector.yaml`: This file defines the Pulsar IO source.
    *   `name`: A descriptive name for the connector instance (e.g., `kinesis-source`).
    *   `type`: Should be set to `kinesis` to use Pulsar's built-in Kinesis connector. (Note: If a specific `className` is required, that should be used instead of or in addition to `type`).
    *   `topic`: The target Pulsar topic where records from Kinesis will be published (e.g., `persistent://public/default/kinesis-input-topic`).
    *   `configFile`: Points to the Kinesis-specific configuration file (e.g., `config.sample.yml` or a renamed `config.kinesis.yml`).
    *   `secretKeys`: Lists any configuration keys that contain sensitive information, such as `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`.
    *   `parallelism`: The number of instances of this connector to run.

2.  `config.sample.yml` (or the file specified in `configFile`): This file configures the Kinesis source connector itself. Key parameters include:
    *   `kinesisStreamName`: The name of the AWS Kinesis stream to consume from.
    *   `awsRegion`: The AWS region where the Kinesis stream is located (e.g., `us-east-1`).
    *   `awsAccessKeyId`: Your AWS access key ID. This is typically injected via the `secretKeys` mechanism.
    *   `awsSecretAccessKey`: Your AWS secret access key. This is also typically injected via `secretKeys`.
    *   `initialPositionInStream` (Optional): Where in the stream to start consuming (e.g., `LATEST`, `TRIM_HORIZON`). Defaults to `LATEST`.
    *   `applicationName` (Optional): A name for the Kinesis consumer application. Useful for tracking progress within Kinesis.
    *   `kinesisEndpoint` (Optional): Allows specifying a custom Kinesis endpoint, useful for VPC environments or LocalStack testing.
    *   `cloudwatchEndpoint` (Optional): Allows specifying a custom CloudWatch endpoint if needed.

Refer to the provided `config.sample.yml` for a base structure and the official Pulsar Kinesis connector documentation for all possible parameters.

## Setup

1.  Ensure the Pulsar Kinesis connector NAR file (e.g., `pulsar-io-kinesis.nar`) is installed in your Pulsar `connectors` directory.
2.  Configure `connector.yaml` and your Kinesis-specific config file (`config.sample.yml` or similar) with your Kinesis stream details and AWS credentials.
3.  Ensure AWS credentials (`awsAccessKeyId`, `awsSecretAccessKey`) are properly set up in Pulsar's secrets management and referenced in `connector.yaml` under `secretKeys`.
4.  Deploy the connector to your Pulsar cluster.

Records consumed from the specified Kinesis stream will be published to the configured Pulsar topic.
