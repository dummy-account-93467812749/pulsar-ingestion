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

### Vehicle Telemetry Processing (CMF)

A key application of translator functions within this project is the processing of vehicle telemetry data. To standardize this data from various sources, a **Vehicle Telemetry Common Message Format (CMF)** has been introduced.

**1. Vehicle Telemetry Common Message Format (CMF)**

*   **Purpose:** The CMF aims to provide a unified structure for telemetry data ingested from different providers (e.g., Geotab, CalAmp, Ford). This standardization simplifies downstream processing, analytics, and storage.
*   **Key Structures:**
    *   `CommonMessageFormat<T>`: The generic envelope for all CMF messages. It includes common fields like `sourceType`, `vehicleId`, `eventTimestamp`, `receivedTimestamp`, and the actual telemetry payload.
    *   `CommonTelemetry`: A standardized structure within `CommonMessageFormat` for common telemetry signals like latitude, longitude, speed, odometer, ignition status, fuel level, etc.
    *   `CommonEvents`: Defines a standard way to represent discrete events from the vehicle (e.g., ignition on/off, harsh braking). (Future enhancement, less focus in current implementation).
    *   `SourceType`: An enum (e.g., `GEOTAB`, `CALAMP`, `FORD`) indicating the original data source, present in `CommonMessageFormat`.
    *   `sourceSpecificData: T`: The generic type parameter `T` in `CommonMessageFormat<T>` allows for the inclusion of raw or additional processed data specific to the original source, ensuring no data loss and providing context for unique vendor attributes.
*   **Output Topic:** All telemetry messages transformed into CMF are routed to a central Pulsar topic:
    *   `persistent://acme/ingest/vehicle-telemetry-common-format`

**2. Vehicle Telemetry Translators**

These Pulsar Functions are responsible for converting source-specific vehicle telemetry messages into the CMF. Each translator is designed for a particular data provider.

*   **GeotabTranslator:**
    *   **Role:** Converts Geotab's specific JSON format into `CommonMessageFormat<GeotabInputMessage.SourceSpecificData>`.
    *   **Input Topic:** Consumes raw Geotab messages from `raw-kinesis-events`.
*   **CalAmpTranslator:**
    *   **Role:** Converts CalAmp's specific JSON format into `CommonMessageFormat<CalAmpInputMessage.SourceSpecificData>`.
    *   **Input Topic:** Consumes raw CalAmp messages from `raw-kafka-events`.
*   **FordTranslator:**
    *   **Role:** Converts Ford's specific JSON format (e.g., from Ford Pro Telematics) into `CommonMessageFormat<FordInputMessage.SourceSpecificData>`.
    *   **Input Topic:** Consumes raw Ford messages from `raw-http-events`.

**3. Data Flow Update**

The introduction of the CMF and its associated translators modifies the data flow for vehicle telemetry as follows:

1.  Raw, source-specific vehicle data (e.g., from Geotab, CalAmp, Ford) is ingested into dedicated "raw" Pulsar topics (`raw-kinesis-events`, `raw-kafka-events`, `raw-http-events` respectively) by the appropriate source connectors.
2.  The respective Vehicle Telemetry Translator function (e.g., `GeotabTranslator`) consumes messages from its designated raw input topic.
3.  The translator function transforms the source-specific message into the `CommonMessageFormat`.
4.  The resulting CMF message is then published to the common output topic: `persistent://acme/ingest/vehicle-telemetry-common-format`.
5.  Downstream consumers, such as an `event-type-splitter` function or analytics jobs, can now subscribe to this single, standardized topic to process telemetry data from all supported vehicle sources. This simplifies downstream logic as these components only need to understand the CMF structure.

This CMF-based approach enhances modularity and makes it easier to integrate new vehicle telemetry sources in the future by simply developing a new translator for that source.

---

*Further sections on Deployment, etc., would follow in a complete architecture document.*
