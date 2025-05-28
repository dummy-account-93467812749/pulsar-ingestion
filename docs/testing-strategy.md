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

Integration testing is crucial for verifying the correct setup and operation of each Pulsar IO connector.

### Centralized Test Location

All integration tests for connectors are located in the `connectors/test/` directory, with subdirectories for each connector type (e.g., `connectors/test/azure-eventhub/`, `connectors/test/http/`).

### General Approach

The general strategy for connector integration tests is as follows:

1.  **Pulsar Instance:** A Pulsar standalone instance is provisioned for testing, typically using [Testcontainers](https://www.testcontainers.org/) (`org.testcontainers.containers.PulsarContainer`). This ensures a clean, isolated Pulsar environment for each test run.
2.  **External Services:**
    *   For connectors interacting with external systems like Kafka, RabbitMQ, or Kinesis-compatible services (e.g., LocalStack), Testcontainers are also used to spin up these services.
    *   For cloud services like Azure Event Hubs, tests are designed to run against a live instance. Connection details (FQDN, entity name, connection strings/keys) for these services are supplied via environment variables (e.g., `TEST_AZURE_EVENTHUBS_FQDN`, `TEST_AZURE_EVENTHUBS_CONNECTION_STRING`). Tests that require these live services are annotated to be skipped (e.g., using JUnit 5's `@DisabledIfEnvironmentVariableMissing`) if the necessary environment variables are not set. This allows tests to be committed to the repository and run in CI environments where secrets can be injected, while not failing in local environments without these credentials.
3.  **Connector Deployment:** The Pulsar connector under test is programmatically deployed to the Testcontainers-managed Pulsar instance using the Pulsar Admin API. This involves providing the connector's `.yaml` definition file and its specific configuration file (e.g., `config.amqp.yml`, `config.http.yml`). For tests requiring credentials or specific endpoints, a temporary configuration file is often generated during test setup, populated with details from environment variables or Testcontainer-provided URIs.
4.  **Data Flow Verification:**
    *   **For Source Connectors:** Test data is produced to the external system (e.g., messages sent to an Event Hub, an HTTP POST request made to the Netty HTTP connector's endpoint). A Pulsar client is then used to consume messages from the target Pulsar topic. Assertions are made to verify that the correct number of messages and the expected content are received in Pulsar.
    *   **For Sink Connectors (if any):** Test data is produced to a Pulsar topic. A client for the external system is used to consume or verify the data in the external system. Assertions confirm the data integrity.
5.  **Test Isolation:** Each test class or method should manage its resources (Pulsar clients, external service clients, temporary configurations) to ensure proper cleanup after execution.

### Specific Connector Tests

*   **Azure EventHubs (AMQP):** The integration test (`connectors/test/azure-eventhub/AzureEventHubsAmqpIntegrationTest.kt`) verifies the AMQP-based connector. It requires live Azure Event Hubs credentials (via environment variables) to run.
*   **HTTP (Netty):** The integration test (`connectors/test/http/HttpNettyIntegrationTest.kt`) verifies the Netty-based HTTP source. It uses a dynamically allocated port for the HTTP server and sends POST requests to it.
*   **(Upcoming) Kafka, Kinesis, RabbitMQ:** Integration tests for these connectors will follow a similar pattern, using Testcontainers for the external services and verifying data ingestion into Pulsar.

This strategy aims to provide strong confidence in the reliability and correctness of each connector.
