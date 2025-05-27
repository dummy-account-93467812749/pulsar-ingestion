# Pulsar Ingestion Monorepo

This monorepo houses Pulsar IO connectors and Pulsar Functions for building robust and scalable data ingestion pipelines.

## Data Pipeline Overview

This project implements a flexible data processing pipeline designed to ingest events from a variety of sources, transform them into a common format, and then route them for further processing or storage. Key sources include AWS Kinesis, Apache Kafka, RabbitMQ, HTTP endpoints, and gRPC services.

The core of the pipeline leverages Apache Pulsar Functions for:
*   **Translation:** Converting source-specific data schemas into a standardized `CommonEvent` format.
*   **Routing:** Dynamically dispatching `CommonEvent` messages to different downstream topics based on event type.

**For detailed information on the pipeline architecture, individual components (connectors and functions), deployment configurations, and instructions on how to run the system locally, please see the [Deployment Documentation](deployment/README.md).**

## Modules

*   `common/`: Shared code, schemas, and utilities.
*   `test-kit/`: Shared test helpers, MockContext, Testcontainers base.
*   `connectors/`: Pulsar IO source connectors.
    *   `kinesis-source/`: Connector for AWS Kinesis.
    *   `rabbitmq-source/`: Connector for RabbitMQ.
    *   `grpc-source/`: Thin Kotlin connector for gRPC sources.
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

## Building

To build the project, run:

```bash
./gradlew build
```

For more detailed instructions on building, testing, and working with subprojects, please see [`BUILDING.md`](BUILDING.md).

## Contributing

Details on contributing will be added soon.
