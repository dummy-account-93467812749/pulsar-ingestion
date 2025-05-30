# Architecture Overview

This document outlines the architecture of the Pulsar Ingestion project, focusing on its data flow, components, and design principles.

## Core Principles

*   **Modularity:** Connectors and functions are designed as independent modules. For example, translator functions under `pulsar-components/cmf/` are implemented as individual submodules, each building a separate artifact.
*   **Extensibility:** The framework allows for easy addition of new source connectors and processing functions.
*   **Scalability:** Leveraging Pulsar's native scalability for message ingestion and processing.
*   **Testability:** Emphasis on unit and integration testing for each component. Integration tests for all connectors are centralized in the `pulsar-components/connectors/test/` directory. (Note: This path might need verification if connector tests are structured differently).

## System Components

The system primarily consists of:
1.  **Source Connectors:** Responsible for ingesting data from various external systems into Pulsar topics.
2.  **Processing Functions (Optional):** Pulsar Functions that can be used to transform, enrich, or route data between topics.
3.  **Pulsar Clusters:** The core messaging backbone.

## Source Connectors

This section details the source connectors developed for ingesting data from different systems. Connectors have been refactored to a new standard configuration schema, detailed in the main project `README.md`. Each connector resides in its own directory under `pulsar-components/connectors/<connector-id>/` and includes a `connector.yaml` for Pulsar integration and a primary connector-specific configuration file (e.g., `config.sample.yml`, `config.http.yml`). These individual connector definitions are discovered and processed by the central `./gradlew generateManifests` task to produce deployment artifacts.

### 1. HTTP Connector

*   **Purpose:** Consumes data from a specified HTTP endpoint and ingests it into a Pulsar topic.
*   **Status:** Uses Pulsar's native Netty source connector, configured for HTTP. This is a configuration-only connector from the project's perspective.
*   **Configuration:** Configuration is defined in `pulsar-components/connectors/http/connector.yaml` (to specify the `netty` type and Pulsar topic) and `pulsar-components/connectors/http/config.http.yml` (for Netty HTTP server settings like host and port). Note: This connector now listens for incoming HTTP POST requests, a change from previous polling behavior.

### 2. Kafka Connector

*   **Purpose:** Consumes messages from Apache Kafka topics and ingests them into Pulsar topics.
*   **Status:** Config-only connector, uses native Pulsar IO. Configuration in YAML files.
*   **Configuration:** Follows the new standard schema: `pulsar-components/connectors/kafka/connector.yaml` and `pulsar-components/connectors/kafka/config.sample.yml`.

### 3. Azure Event Hub Connector

*   **Purpose:** Consumes events from Azure Event Hubs and ingests them into Pulsar topics.
*   **Status:** Uses a standard Pulsar AMQP 1.0 source connector. This is a configuration-only connector from the project's perspective.
*   **Configuration:** Configuration is defined in `pulsar-components/connectors/azure-eventhub/connector.yaml` (to specify the `amqp` type and Pulsar topic) and `pulsar-components/connectors/azure-eventhub/config.sample.yml` (for AMQP server details, authentication, and Event Hub source address).

### 4. Pulsar Connector

*   **Purpose:** Consumes messages from one Pulsar topic/pattern and ingests them into another Pulsar topic.
*   **Status:** Custom connector for Pulsar-to-Pulsar use cases, source code and tests maintained.
*   **Configuration:** Follows the new standard schema: `pulsar-components/connectors/pulsar/connector.yaml` and `pulsar-components/connectors/pulsar/config.sample.yml`.

### 5. gRPC Connector

*   **Purpose:** Consumes data from gRPC services.
*   **Status:** Custom gRPC connector with source code available (see `pulsar-components/connectors/grpc/build.gradle.kts`).
*   **Configuration:** Follows `pulsar-components/connectors/grpc/connector.yaml` and `pulsar-components/connectors/grpc/config.sample.yml`.

### 6. Kinesis Connector

*   **Purpose:** Consumes data from AWS Kinesis streams.
*   **Status:** Config-only connector, uses native Pulsar IO. Configuration in YAML files.
*   **Configuration:** Follows `pulsar-components/connectors/kinesis/connector.yaml` and `pulsar-components/connectors/kinesis/config.sample.yml`.

### 7. RabbitMQ Connector

*   **Purpose:** Consumes messages from RabbitMQ queues.
*   **Status:** Config-only connector, uses native Pulsar IO. Configuration in YAML files.
*   **Configuration:** Follows `pulsar-components/connectors/rabbitmq/connector.yaml` and `pulsar-components/connectors/rabbitmq/config.sample.yml`.

## Processing Functions

Pulsar Functions are used for tasks like message transformation and enrichment.

### Translator Functions
Located under `pulsar-components/cmf/` (Common Message Format translators), these functions are responsible for converting messages from various source formats into a common schema.
-   **Structure:** The `pulsar-components/cmf/` directory acts as a parent for multiple individual translator submodules (e.g., `user-profile-translator`, `order-record-translator` located under `pulsar-components/cmf/user-profile-translator/`, etc.).
-   **Artifacts:** Each translator submodule is independently built and produces its own lean NAR file (Pulsar Archive). These NARs are then collected by the `bundleForDeploy` task for deployment.
-   **Testing:** Unit tests are specific to each translator submodule. Shared integration tests for translators are located in the `pulsar-components:cmf:translators-integration` module.

---

*Further sections on Deployment, etc., would follow in a complete architecture document.*
