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

This section details the source connectors developed for ingesting data from different systems. All connectors are currently in a **partially implemented** state and are **not yet buildable or fully testable** due to various outstanding issues.

### 1. HTTP Source Connector

*   **Purpose:** Consumes data from a specified HTTP endpoint and ingests it into a Pulsar topic.
*   **Status:** Partially implemented. Blocked by a persistent test compilation error.
*   **Configuration (`http-source.yml`):**
    *   Location: `connectors/http-source/src/main/resources/http-source.yml`
    *   Main Parameters: `url`, `method`, `headers`, `requestBody`, `pollingIntervalMs`, `connectTimeoutMs`, `readTimeoutMs`.
*   **Core Implementation Details:**
    *   Classes: `HttpSource.kt`, `HttpSourceConfig.kt`.
    *   Approach: Uses Ktor HTTP client. Constructor accepts `HttpClientEngine` for testability. Timeouts via `HttpTimeout` plugin.
*   **Outstanding Issues & Next Steps for Completion (as of last build attempt - Session 3, Turn 17):**
    *   **Primary Blocker:** Persistent "Unresolved reference 'ConnectorType'" in `HttpSourceIntegrationTest.kt` (line 62: `val connector = server.environment.connectors.first { it.type == io.ktor.server.engine.ApplicationEngine.ConnectorType.SERVER }`). This occurs despite the `io.ktor.server.engine.ApplicationEngine` import and using the fully qualified name. Multiple clean builds have not resolved this.
    *   The `MockEngine` argument mismatch in `HttpSourceTest.kt` (expecting `HttpClientEngineFactory` vs. `HttpClientEngine`) was also a recurring issue, likely due to the build system not picking up the latest `HttpSource.kt` constructor (which accepts `HttpClientEngine`). The `ConnectorType` error currently prevents re-verification of this.
    *   Complete and stabilize unit and integration tests once compilation is successful.
*   **Unit Test Status:** Drafted for config loading (`HttpSourceConfigTest.kt`) and source logic (`HttpSourceTest.kt`). `HttpSourceTest.kt` was refactored to inject a `MockEngine`. Blocked by overall test compilation.
*   **Integration Test Status:** `HttpSourceIntegrationTest.kt` was designed to use an embedded Ktor server. Blocked by the `ConnectorType` compilation error.

### 2. Kafka Source Connector

*   **Purpose:** Consumes messages from Apache Kafka topics and ingests them into Pulsar topics.
*   **Status:** Partially implemented. Blocked by Kotlin compilation errors and Spotless violations.
*   **Configuration (`kafka-source.yml`):**
    *   Location: `connectors/kafka-source/src/main/resources/kafka-source.yml`
    *   Main Parameters: `bootstrapServers`, `topic`, `groupId`, `keyDeserializer`, `valueDeserializer`, `autoOffsetReset`, security settings, etc.
*   **Core Implementation Details:**
    *   Classes: `KafkaSource.kt`, `KafkaSourceConfig.kt`.
    *   Approach: Uses `org.apache.kafka.clients.consumer.KafkaConsumer`. Refactored with an internal constructor for `KafkaConsumer` injection in tests. `KafkaPulsarRecord` inner class adapts Kafka messages.
*   **Outstanding Issues & Next Steps for Completion (as of last build attempt for Kafka - Session 2, Turn 15):**
    *   **Module Added to Build:** `connectors:kafka-source` was added to `settings.gradle.kts` (Session 3, Turn 14).
    *   **Spotless Violations:** Build in Session 2 (Turn 15) indicated formatting issues in `KafkaSource.kt`, `KafkaSourceConfig.kt`, and `KafkaSourceConfigTest.kt`. Run `spotlessApply` first.
    *   **Compilation Errors in `KafkaSource.kt` (from Session 2, Turn 15):**
        *   "Supertype initialization is impossible without a primary constructor" for `KafkaSource`. The primary constructor `class KafkaSource : PushSource<ByteArray>()` needs to be correctly defined, potentially by ensuring `PushSource()` is parameterless or by providing appropriate parameters if the base class requires them. The internal constructor for testing should be a `secondary constructor`.
        *   "Overrides nothing" errors in the inner `KafkaPulsarRecord` class for methods like `getSequenceId`, `getRecordContext`. Signatures need to be precisely matched with Pulsar's `org.apache.pulsar.functions.api.Record` and `org.apache.pulsar.io.core.RecordContext` interfaces (including Java `Optional` usage for return types if methods originate from Java interfaces).
        *   "Unresolved reference 'RecordContext'" in `KafkaPulsarRecord` (missing import for `org.apache.pulsar.io.core.RecordContext`).
        *   `recordMetric` argument type mismatch (Int vs Double) in `KafkaPulsarRecord.PulsarRecordContext` (likely meant to be `KafkaRecordContext`).
    *   Fix compilation errors.
    *   Verify unit tests pass.
    *   Implement integration tests using Testcontainers for Kafka.
    *   Confirm module builds with the main project.
*   **Unit Test Status:** Drafted for config (`KafkaSourceConfigTest.kt`) and source logic (`KafkaSourceTest.kt`). Blocked by `KafkaSource.kt` compilation errors.
*   **Integration Test Status:** Pending.

### 3. Azure Event Hubs Source Connector

*   **Purpose:** Consumes events from Azure Event Hubs and ingests them into Pulsar topics.
*   **Status:** Code drafted. Not yet added to `settings.gradle.kts` or built.
*   **Configuration (`azure-eventhub-source.yml`):**
    *   Location: `connectors/azure-eventhub-source/src/main/resources/azure-eventhub-source.yml`
    *   Main Parameters: `fullyQualifiedNamespace`, `eventHubName`, `consumerGroup`, `connectionString` or `credential` details, `checkpointStore` (Azure Blob Storage) configuration.
*   **Core Implementation Details:**
    *   Classes: `AzureEventHubSource.kt`, `AzureEventHubSourceConfig.kt`.
    *   Approach: Uses Azure SDK's `EventProcessorClient` with `BlobCheckpointStore`.
*   **Outstanding Issues & Next Steps for Completion (as of end of Session 2):**
    *   **Add to `settings.gradle.kts`**.
    *   **Initial Build & Compile:** Perform the first build to identify and fix compilation errors and Spotless violations.
    *   **Unit Test Refinement:** `AzureEventHubSourceTest.kt` is conceptual and needs refactoring for proper mocking of `EventProcessorClientBuilder` (e.g., via injection).
    *   **Integration Tests:** Develop integration tests (e.g., using Testcontainers with Azurite).
*   **Unit Test Status:** Conceptual draft for config and source. Not yet compiled or run.
*   **Integration Test Status:** Pending.

### 4. Pulsar Source Connector

*   **Purpose:** Consumes messages from one Pulsar topic/pattern and ingests them into another Pulsar topic.
*   **Status:** Partially implemented. Module added to `settings.gradle.kts` (Session 3, Turn 14). Blocked by Kotlin compilation errors from previous attempts.
*   **Configuration (`pulsar-source.yml`):**
    *   Location: `connectors/pulsar-source/src/main/resources/pulsar-source.yml`
    *   Main Parameters: `serviceUrl`, `topicNames` / `topicsPattern`, `subscriptionName`, `subscriptionType`, auth/TLS settings.
*   **Core Implementation Details:**
    *   Classes: `PulsarSource.kt`, `PulsarSourceConfig.kt`.
    *   Approach: Uses `PulsarClient` and `Consumer`. Includes test constructor for mock client/consumer.
*   **Outstanding Issues & Next Steps for Completion (as of last build attempt for Pulsar - Session 2, Turn 15):**
    *   **Spotless Violations:** Build in Session 2 (Turn 15) indicated formatting issues. Run `spotlessApply`.
    *   **Compilation Errors in `PulsarSource.kt` (from Session 2, Turn 15):**
        *   "Overrides nothing" errors in `PulsarSourceRecord` and `PulsarRecordContext` due to signature mismatches with Pulsar IO interfaces (e.g., `getSequenceId`, `getRecordContext`). Specifically, `PulsarRecordContext` methods like `getPartitionId`, `getRecordSequence`, `ack`, `fail`, `getAckFuture`, `getNackFuture` were flagged.
        *   "Unresolved reference 'RecordContext'" (missing import for `org.apache.pulsar.io.core.RecordContext`).
        *   `recordMetric` argument type mismatch.
    *   **`sourceContext.getPulsarConsumer()` Placeholder:** The ack/fail logic in `PulsarSourceRecord` and `PulsarRecordContext` needs to correctly use the main `consumer` instance from `PulsarSource`. The `PulsarSource.processMessage` method already handles ack/nack on the main consumer instance; this should be leveraged.
    *   Fix compilation errors.
    *   Verify unit tests pass.
    *   Implement integration tests using Testcontainers for Pulsar.
*   **Unit Test Status:** Drafted for config (`PulsarSourceConfigTest.kt`) and source logic (`PulsarSourceTest.kt`). Blocked by `PulsarSource.kt` compilation errors.
*   **Integration Test Status:** Pending.

---

*Further sections on Processing Functions, Deployment, etc., would follow in a complete architecture document.*
