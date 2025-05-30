# Testing Strategy

This document outlines the testing strategy for the Pulsar Ingestion project, covering unit and integration tests.

## Overview

Our testing approach emphasizes:
*   **Component Isolation:** Testing individual pieces of functionality where possible.
*   **End-to-End Verification:** Ensuring data flows correctly through connectors from the source system into Pulsar.
*   **Automation:** Leveraging automated tests within a CI/CD pipeline.

## Unit Testing

Unit tests are focused on specific classes or functions. With the move towards using native Pulsar connectors for many sources (like HTTP via Netty, EventHubs via AMQP, Kafka, Kinesis, RabbitMQ), the amount of custom code requiring dedicated unit tests within this project has decreased. Any remaining custom utility classes or complex configuration parsing logic (if any) should be covered by JUnit 5 tests in Kotlin.

## Integration Testing

Integration testing is crucial for verifying the correct setup and operation of Pulsar IO connectors and custom Pulsar Functions.

### Test Locations and General Approach

Integration tests for components are typically co-located with the component's source code (e.g., within `src/test/kotlin` of the specific module like `pulsar-components/filterer/`) or reside in dedicated shared integration test modules (e.g., `pulsar-components/cmf/translators-integration/` for translator functions). The previous centralized model of having all connector tests in a single `connectors/test/` directory is no longer accurate.

The general strategy for component integration tests is as follows:

The general strategy for connector integration tests is as follows:

1.  **Pulsar Instance:** A Pulsar standalone instance is provisioned for testing, typically using [Testcontainers](https://www.testcontainers.org/) (`org.testcontainers.containers.PulsarContainer`). This ensures a clean, isolated Pulsar environment for each test run.
2.  **External Services:**
    *   For connectors interacting with external systems like Kafka, RabbitMQ, or Kinesis-compatible services (e.g., LocalStack), Testcontainers are also used to spin up these services.
    *   For cloud services like Azure Event Hubs, tests that might interact with them (even if indirectly via a pipeline) are designed to run against a live instance if necessary. Connection details would be supplied via environment variables (e.g., `TEST_AZURE_EVENTHUBS_FQDN`, `TEST_AZURE_EVENTHUBS_CONNECTION_STRING`). Tests requiring live services should be annotated to be skipped (e.g., using JUnit 5's `@DisabledIfEnvironmentVariableMissing`) if the necessary environment variables are not set.
3.  **Component Deployment:** The Pulsar connector or function under test is programmatically deployed to the Testcontainers-managed Pulsar instance using the Pulsar Admin API. This involves providing the component's definition (e.g., connector's `.yaml` file, function metadata) and its specific configuration. For tests requiring credentials or specific endpoints, temporary configuration files might be generated during test setup.
4.  **Data Flow Verification:**
    *   **For Source Connectors (if directly tested):** Test data is produced to the external system. A Pulsar client consumes messages from the target Pulsar topic. Assertions verify message count and content.
    *   **For Functions:** Test data is produced to the function's input topic(s). A Pulsar client consumes messages from the function's output topic(s) or checks for side-effects (like messages routed to dynamic topics). Assertions verify the function's logic.
    *   **For Sink Connectors (if any):** Test data is produced to a Pulsar topic. A client for the external system is used to consume or verify the data in the external system. Assertions confirm the data integrity.
5.  **Test Isolation:** Each test class or method should manage its resources (Pulsar clients, external service clients, temporary configurations) to ensure proper cleanup after execution.

### Specific Component Tests

*   **Source Connectors (`pulsar-components/connectors/`):**
    *   Connectors such as Azure Event Hubs (AMQP), HTTP (Netty), Kafka, Kinesis, and RabbitMQ are primarily configured using YAML and leverage Pulsar's native connector runtimes or well-established libraries.
    *   Currently, these specific connector modules do not contain dedicated integration test suites within their own `src/test` directories (e.g., no `AzureEventHubsAmqpIntegrationTest.kt` was found directly in the `azure-eventhub` module).
    *   Their testing often relies on the robustness of the underlying Pulsar connector framework and they are typically tested implicitly as part of broader end-to-end pipeline tests that involve functions like translators or splitters.
    *   If dedicated integration tests are added for these connectors in the future, they would reside within their respective module directories (e.g., `pulsar-components/connectors/kafka/src/test/...`).

*   **Filterer Function (`pulsar-components/filterer/`):**
    *   The Event Type Splitter function has its own integration tests located at `pulsar-components/filterer/src/test/kotlin/com/example/pulsar/filterer/EventTypeSplitterIntegrationTest.kt`. These tests verify its routing logic using a Testcontainers-managed Pulsar instance.

*   **Translator Functions (`pulsar-components/cmf/`):**
    *   Translator functions share a common set of integration tests located in the `pulsar-components/cmf/translators-integration/` module. The main test file is `src/test/kotlin/com/example/pulsar/functions/transforms/translators/TranslatorsIntegrationTest.kt`. These tests cover the general translation and CMF conversion logic.

This strategy aims to provide strong confidence in the reliability and correctness of each component, focusing tests on custom code and critical integration points.
