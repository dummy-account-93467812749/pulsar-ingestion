# Architecture Overview

This document outlines the architecture of the Pulsar Ingestion project, focusing on its data flow, components, and design principles.

## Core Principles

*   **Modularity:** Connectors and functions are designed as independent modules.
*   **Extensibility:** The framework allows for easy addition of new source connectors and processing functions.
*   **Scalability:** Leveraging Pulsar's native scalability for message ingestion and processing.
*   **Testability:** Emphasis on unit and integration testing for each component.

## System Components

The system primarily consists of:
1.  **Source Connectors:** Responsible for ingesting data from various external systems into Pulsar topics.
2.  **Processing Functions (Optional):** Pulsar Functions that can be used to transform, enrich, or route data between topics.
3.  **Pulsar Clusters:** The core messaging backbone.

## Source Connectors

This section details the source connectors developed for ingesting data from different systems. Connectors have been refactored to a new standard configuration schema, detailed in the main project `README.md`. Each connector resides in its own directory under `connectors/<connector-id>/` and includes a `connector.yaml` for Pulsar integration and a `config.sample.yml` for connector-specific settings.

### 1. HTTP Connector

*   **Purpose:** Consumes data from a specified HTTP endpoint and ingests it into a Pulsar topic.
*   **Status:** Custom connector, source code and tests maintained.
*   **Configuration:** Follows the new standard schema: `connectors/http/connector.yaml` and `connectors/http/config.sample.yml`.

### 2. Kafka Connector

*   **Purpose:** Consumes messages from Apache Kafka topics and ingests them into Pulsar topics.
*   **Status:** Config-only connector, uses native Pulsar IO. Configuration in YAML files.
*   **Configuration:** Follows the new standard schema: `connectors/kafka/connector.yaml` and `connectors/kafka/config.sample.yml`.

### 3. Azure Event Hub Connector

*   **Purpose:** Consumes events from Azure Event Hubs and ingests them into Pulsar topics.
*   **Status:** Custom connector, source code and tests maintained.
*   **Configuration:** Follows the new standard schema: `connectors/azure-eventhub/connector.yaml` and `connectors/azure-eventhub/config.sample.yml`.

### 4. Pulsar Connector

*   **Purpose:** Consumes messages from one Pulsar topic/pattern and ingests them into another Pulsar topic.
*   **Status:** Custom connector for Pulsar-to-Pulsar use cases, source code and tests maintained.
*   **Configuration:** Follows the new standard schema: `connectors/pulsar/connector.yaml` and `connectors/pulsar/config.sample.yml`.

### 5. gRPC Connector

*   **Purpose:** Consumes data from gRPC services.
*   **Status:** Custom gRPC connector. Note: The basic structure is in place, but the specific source code implementation is currently missing.
*   **Configuration:** Follows `connectors/grpc/connector.yaml` and `connectors/grpc/config.sample.yml`.

### 6. Kinesis Connector

*   **Purpose:** Consumes data from AWS Kinesis streams.
*   **Status:** Config-only connector, uses native Pulsar IO. Configuration in YAML files.
*   **Configuration:** Follows `connectors/kinesis/connector.yaml` and `connectors/kinesis/config.sample.yml`.

### 7. RabbitMQ Connector

*   **Purpose:** Consumes messages from RabbitMQ queues.
*   **Status:** Config-only connector, uses native Pulsar IO. Configuration in YAML files.
*   **Configuration:** Follows `connectors/rabbitmq/connector.yaml` and `connectors/rabbitmq/config.sample.yml`.

---

*Further sections on Processing Functions, Deployment, etc., would follow in a complete architecture document.*
