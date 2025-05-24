# Pulsar Ingestion Monorepo

This monorepo houses Pulsar IO connectors and Pulsar Functions for building robust and scalable data ingestion pipelines.

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
*   `deployment/`: Deployment configurations.
    *   `k8s/`: Kubernetes configurations (Function Mesh and worker mode).
    *   `helm/`: Optional Helm chart.
    *   `logging/`: Logging configurations.
*   `local-dev/`: Tools and scripts for local development.
*   `docs/`: Project documentation.

## Building

To build the project, run:

```bash
./gradlew build
```

## Contributing

Details on contributing will be added soon.
