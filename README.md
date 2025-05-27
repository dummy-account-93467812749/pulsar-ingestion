# Pulsar Ingestion Monorepo

This monorepo houses Pulsar IO connectors and Pulsar Functions for building robust and scalable data ingestion pipelines.

## Data Pipeline Overview

This project implements a flexible data processing pipeline designed to ingest events from a variety of sources, transform them into a common format, and then route them for further processing or storage. Key sources include Azure Event Hubs, Apache Kafka, AWS Kinesis, RabbitMQ, HTTP endpoints, and gRPC services.

The core of the pipeline leverages Apache Pulsar Functions for:
*   **Translation:** Converting source-specific data schemas into a standardized `CommonEvent` format.
*   **Routing:** Dynamically dispatching `CommonEvent` messages to different downstream topics based on event type.

**For detailed information on the pipeline architecture, individual components (connectors and functions), deployment configurations, and instructions on how to run the system locally, please see the [Deployment Documentation](deployment/README.md).**

## Modules

*   `common/`: Shared code, schemas, and utilities.
*   `test-kit/`: Shared test helpers, MockContext, Testcontainers base.
*   `connectors/`: Pulsar IO source connectors.
    *   `azure-eventhub/`: Custom connector for Azure Event Hubs.
    *   `grpc/`: Custom gRPC connector (Note: source code currently missing).
    *   `http/`: Custom HTTP source connector.
    *   `kafka/`: Config-only connector for Apache Kafka (uses native Pulsar IO).
    *   `kinesis/`: Config-only connector for AWS Kinesis (uses native Pulsar IO).
    *   `pulsar/`: Custom connector for Pulsar-to-Pulsar use cases.
    *   `rabbitmq/`: Config-only connector for RabbitMQ (uses native Pulsar IO).
*   `functions/`: Pulsar Functions for message processing.
    *   `splitter/`: Implements the EIP Splitter pattern.
    *   `transforms/`: For various message transformations.
        *   `stateless/`: Stateless transformations.
        *   `stateful/`: Stateful transformations (low-priority).
*   `deployment/`: Houses all deployment configurations and the primary pipeline definition.
    *   `deployment/pipeline.yaml`: The single source of truth for defining the entire pipeline.
    *   `deployment/helm/`: Contains Helm chart templates used to generate various deployment manifests (for FunctionMesh, Kubernetes Jobs, and local Docker Compose).
    *   `deployment/local-dev/`: Contains the Docker Compose setup for running a local development environment. The `bootstrap.sh` script for this environment is auto-generated from `pipeline.yaml`.
    *   Generated manifests for Kubernetes (FunctionMesh and worker-style) are placed in `build/deploy/` by the `./gradlew generateManifests` task.
    *   For detailed information on the deployment structure, how to modify the pipeline, and run it locally, please see the comprehensive guide in [deployment/README.md](deployment/README.md).
*   `docs/`: Project documentation.

## Connector Configuration Schema

Each connector resides in its own subdirectory under `connectors/` named with a unique `<connector-id>` in kebab-case (e.g., `connectors/kafka`). Each connector directory must contain the following:

1.  **`connector.yaml`**: Defines the connector's metadata and Pulsar integration.
    *   `name` (string, required): The unique name of the connector. This **must** match the `<connector-id>` (folder name).
    *   `type` (string, required): Specifies if the connector is a `source` or a `sink`.
    *   `image` (string, optional): The Docker image to use for this connector (e.g., `apachepulsar/pulsar-io-kafka:latest`).
        *   For **custom-code** connectors built within this monorepo, this field should be left blank (`""`). The build process will handle packaging.
        *   For **native Pulsar IO** connectors (config-only), this field is typically omitted or left blank, as Pulsar uses its own runtime and the connector's NAR file (Pulsar Archive). The `type` field (e.g., `kafka`, `kinesis`) or a specific `className` (if needed, though not standard in this schema) in the configuration usually suffices for Pulsar to locate the built-in connector.
    *   `topic` (string, required): The Pulsar topic to which this connector will produce messages (for sources) or consume messages from (for sinks). Format: `persistent://<tenant>/<namespace>/<topic-name>`.
    *   `configFile` (string, required): The relative path to the connector's specific configuration file within its directory. Typically `config.sample.yml`.
    *   `secretKeys` (array of strings, optional): A list of environment variable names that contain sensitive data (e.g., API keys, passwords). These secrets will be made available to the connector's environment. Example: `["KAFKA_API_KEY", "DATABASE_PASSWORD"]`.
    *   `parallelism` (integer, optional, default: `1`): The number of instances of this connector to run.

2.  **`config.sample.yml`** (or the filename specified in `configFile`): Contains the connector-specific configurations.
    *   This YAML file defines parameters needed by the connector itself (e.g., connection URLs, authentication details, behavioral settings).
    *   It can use `${ENV_VAR}` placeholders for values, especially for those listed in `secretKeys` in `connector.yaml`. These placeholders will be substituted with the actual environment variable values at runtime.

### Connector Types:

*   **Custom-Code Connectors**:
    *   These are developed and built within this monorepo.
    *   They are identifiable by the presence of a `build.gradle.kts` file (or other build script) and a `src/**` directory containing the source code (e.g., Java/Kotlin).
    *   The `image` field in `connector.yaml` should be `""` (blank).
*   **Config-Only Connectors (Native Pulsar IO)**:
    *   These leverage pre-built, native Pulsar IO connectors.
    *   They are identifiable by the *absence* of `build.gradle.kts` and `src/**`. Their directory will primarily contain `connector.yaml` and `config.sample.yml`.
    *   The `image` field in `connector.yaml` is typically omitted or left blank. Pulsar identifies these connectors by their `type` (e.g., `kafka`, `kinesis`, `rabbitmq`) as defined in the Pulsar documentation for built-in connectors.

## Building

To build the project, run:

```bash
./gradlew build
```

For more detailed instructions on building, testing, and working with subprojects, please see [`BUILDING.md`](BUILDING.md).

## Contributing

Details on contributing will be added soon.
