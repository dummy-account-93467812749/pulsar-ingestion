# Message Translator Functions

## Overview

This directory contains individual Pulsar Function submodules, each designed to translate messages from various source-specific JSON formats into a standardized `CommonEvent` JSON schema. This aligns with the Message Translator pattern, facilitating consistent event processing downstream.

The `CommonEvent` schema is defined in the `:common` Gradle module, which is a dependency for each translator submodule.

## Translator Functions Implemented

Each of the following five translator functions is implemented in its own Gradle submodule:

1.  **`UserProfileTranslator`** (Module: `:functions:translators:user-profile-translator`):
    *   Translates events from the "user-service".
    *   Input schema example: `{ "uid": 123, "name": "Alice", "created": 1620000000 }` (epoch seconds for `created`)
2.  **`OrderRecordTranslator`** (Module: `:functions:translators:order-record-translator`):
    *   Translates events from the "order-service".
    *   Input schema example: `{ "orderId": "ORD-456", "items": [...], "placedAt": "2025-05-26T10:00:00Z" }` (ISO 8601 for `placedAt`)
3.  **`InventoryUpdateTranslator`** (Module: `:functions:translators:inventory-update-translator`):
    *   Translates events from the "inventory-service".
    *   Input schema example: `{ "sku": "SKU-789", "qty": 42, "updateTime": 1620050000 }` (epoch seconds for `updateTime`)
4.  **`PaymentNoticeTranslator`** (Module: `:functions:translators:payment-notice-translator`):
    *   Translates events from the "payment-gateway".
    *   Input schema example: `{ "txnId": "TXN-001", "amount": 99.95, "currency": "USD", "time": "2025-05-26T09:30:00Z" }` (ISO 8601 for `time`)
5.  **`ShipmentStatusTranslator`** (Module: `:functions:translators:shipment-status-translator`):
    *   Translates events from the "shipping-service".
    *   Input schema example: `{ "shipId": "SHIP-321", "status": "DELIVERED", "deliveredAt": 1620100000 }` (epoch seconds for `deliveredAt`)

## CommonEvent Schema

All translators output messages conforming to the `CommonEvent` schema:

```json
{
  "eventId": "string",      // Unique ID for this event
  "source":  "string",      // e.g., "user-service", "order-service"
  "eventType": "string",    // e.g., "USER_PROFILE_EVENT", "ORDER_EVENT"
  "timestamp": "ISO8601",   // Timestamp of the event occurrence or processing
  "data": { /* original input JSON payload */ }
}
```

## Building the Functions

Each translator function is its own submodule and produces its own lean JAR file.

To build an individual translator module (e.g., `UserProfileTranslator`):
1.  Navigate to the root of the project.
2.  Run the Gradle build command for that specific submodule:
    ```bash
    # To build the UserProfileTranslator
    ./gradlew :functions:translators:user-profile-translator:build
    ```
3.  The output JAR will be located in the submodule's build directory, for example:
    `functions/translators/user-profile-translator/build/libs/user-profile-translator-VERSION.jar` (replace `VERSION` with the actual project version, e.g., `0.1.0-SNAPSHOT`).

To build all translator modules and other project modules, run the standard build command from the project root:
```bash
./gradlew build
```

## Testing the Functions

*   **Unit Tests:** Each translator submodule contains its own unit tests that test the individual translator logic in isolation. To run unit tests for a specific submodule (e.g., `UserProfileTranslator`):
    ```bash
    # To test the UserProfileTranslator
    ./gradlew :functions:translators:user-profile-translator:test
    ```

*   **Integration Tests:** Integration tests for all translators are located in a dedicated submodule: `:functions:translators:translators-integration`. These tests verify the translators' behavior within an in-memory Pulsar LocalRunner environment. To run these integration tests:
    ```bash
    # To run integration tests for translators
    ./gradlew :functions:translators:translators-integration:test
    ```

**Note on Gradle Wrapper:** Always use the provided Gradle Wrapper (`./gradlew`) for building and testing. Ensure the wrapper scripts (`gradlew` and `gradlew.bat`) are executable if you encounter permission issues.

## Deployment

The translator functions are packaged as lean JAR files, suitable for deployment to a Pulsar cluster. You can deploy each function using the `pulsar-admin functions create` command.

Below is a conceptual example for deploying the `UserProfileTranslator`. Adapt the command for other translators and your specific environment details (tenant, namespace, topic names, JAR path).

```bash
pulsar-admin functions create \
  --jar path/to/your/functions/translators/user-profile-translator/build/libs/user-profile-translator-VERSION.jar \
  --classname com.example.pulsar.functions.transforms.translators.UserProfileTranslator \
  --inputs persistent://public/default/user-profile-input-topic \
  --output persistent://public/default/common-event-output-topic \
  --name UserProfileTranslatorFunction \
  --tenant public \
  --namespace default
  # Add any other necessary configurations like parallelisms, resources, etc.
```

Replace `path/to/your/` and `user-profile-translator-VERSION.jar` with the actual path and filename for the specific translator you are deploying.

**Note on Containerized Deployment:** If deploying via container images (e.g., built using Jib as described in `BUILDING.MD`), your deployment method will differ (e.g., using Function Mesh, Kubernetes manifests, or other container orchestration tools). Refer to the Pulsar documentation and `BUILDING.MD` for more details on Jib and container-based deployment strategies.

## Configuration

*   **Input/Output Topics:** These are specified during deployment using the `--inputs` and `--output` parameters of the `pulsar-admin functions create` command (for JAR-based deployment) or equivalent configuration in your containerized deployment definition.
*   **Source and EventType:** For these translators, the `source` (e.g., "user-service") and `eventType` (e.g., "USER_PROFILE_EVENT") are hardcoded within each respective function as per the requirements.
*   **Function Naming & Other Parameters:** Tenant, namespace, function name, parallelism, and resource allocations are also configured at deployment time.

Refer to the official Pulsar documentation for more details on function configuration and deployment.
