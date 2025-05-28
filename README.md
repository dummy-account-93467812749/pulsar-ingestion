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
*   `deployment/`: Houses deployment configurations and artifacts.
    *   `deployment/pipeline.yaml`: Central configuration file defining functions, tenant, namespace, and overall pipeline structure.
    *   `deployment/worker/`: Contains a standalone Helm chart for deploying the pipeline to Kubernetes using a worker/job-based approach (without FunctionMesh).
    *   `deployment/mesh/`: Contains a standalone Helm chart for deploying the pipeline to Kubernetes using FunctionMesh.
    *   `deployment/compose/`: Contains configurations for running the pipeline locally using Docker Compose, including an auto-generated `bootstrap.sh` script.
    *   The `./gradlew generateManifests` task processes `deployment/pipeline.yaml` and connector configurations from the `/connectors/` directory. It generates:
        *   Kubernetes manifests for worker and FunctionMesh deployments (into the `build/deploy/` directory).
        *   The `deployment/compose/bootstrap.sh` script for local Docker Compose environments.
    *   For detailed information on the deployment structure, generation process, and how to use each deployment method, please see the comprehensive guide in [deployment/README.md](deployment/README.md).
*   `docs/`: Project documentation.

## Connector Configuration Schema

Each connector resides in its own subdirectory under `connectors/` named with a unique `<connector-id>` in kebab-case (e.g., `connectors/kafka`). Each connector directory must contain the following:

1.  **`connector.yaml`**: Defines the connector's metadata and Pulsar integration.
    *   `name` (string, required): The unique name of the connector. This **must** match the `<connector-id>` (folder name).
    *   `type` (string, required for built-in, ignored for custom if `archive` is present): Specifies if the connector is a `source` or a `sink`. For built-in connectors, this refers to the Pulsar-recognized type (e.g., `kafka`, `kinesis`). For custom connectors, this field might be informational if an `archive` is specified.
    *   `image` (string, optional): The Docker image to use for this connector, primarily relevant for FunctionMesh deployments if not using a custom-built NAR.
        *   For **custom-code** connectors built within this monorepo and deployed via `bootstrap.sh` or worker jobs, this field might be used as a fallback if `archive` is not specified, implying a naming convention for the packaged NAR/JAR (e.g., `{{ $name }}.nar`).
        *   For **native Pulsar IO** connectors (config-only), this field is typically omitted. Pulsar uses its own runtime and identifies the connector by its `type` (e.g., `kafka`, `kinesis`).
    *   `archive` (string, optional): Path to the connector's NAR (Pulsar Archive) or JAR file.
        *   **Strongly recommended for custom-code connectors**, especially for deployments managed by `bootstrap.sh` or Kubernetes worker jobs, as this explicitly defines the package to be used. The path is relative to where Pulsar expects to find it (e.g., `/pulsar/connectors/` inside the container for `bootstrap.sh`).
        *   The `generateManifests` task and associated Helm templates prioritize this field for custom connectors.
    *   `topic` (string, required): The Pulsar topic to which this connector will produce messages (for sources) or consume messages from (for sinks). Format: `persistent://<tenant>/<namespace>/<topic-name>`.
    *   `configFile` (string, required): The relative path to the connector's specific configuration file within its directory (e.g., `config.dev.yml`, `config.prod.yml`).
    *   `secretKeys` (array of strings, optional): A list of environment variable names that contain sensitive data (e.g., API keys, passwords). These secrets will be made available to the connector's environment. Example: `["KAFKA_API_KEY", "DATABASE_PASSWORD"]`.
    *   `parallelism` (integer, optional, default: `1`): The number of instances of this connector to run.

2.  **`config.sample.yml`** (or the filename specified in `configFile`): Contains the connector-specific configurations.
    *   This YAML file defines parameters needed by the connector itself (e.g., connection URLs, authentication details, behavioral settings).
    *   It can use `${ENV_VAR}` placeholders for values, especially for those listed in `secretKeys` in `connector.yaml`. These placeholders will be substituted with the actual environment variable values at runtime.

### Connector Types:

*   **Custom-Code Connectors**:
    *   Developed and built within this monorepo (identifiable by `build.gradle.kts` and `src/` in their directory).
    *   **`archive` field**: Should be populated with the name of the NAR/JAR file (e.g., `my-connector.nar`). This is crucial for `bootstrap.sh` and worker job deployments.
    *   **`image` field**: Can be left blank if `archive` is specified and deployments primarily use the archive. If used, it might specify a pre-built Docker image for FunctionMesh or as a fallback for other deployment types.
*   **Config-Only Connectors (Native Pulsar IO)**:
    *   Leverage pre-built, native Pulsar IO connectors (no `build.gradle.kts` or `src/`).
    *   **`type` field**: Essential, as it tells Pulsar which built-in connector to use (e.g., `kinesis`, `kafka`).
    *   **`image` and `archive` fields**: Typically omitted or left blank.

## Building

To build the project, run:

```bash
./gradlew build
```

For more detailed instructions on building, testing, and working with subprojects, please see [`BUILDING.md`](BUILDING.md).

## Contributing

Details on contributing will be added soon.
