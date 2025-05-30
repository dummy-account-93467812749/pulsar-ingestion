# Load Test Verification Playbook

This document outlines the steps to run the load generator for different sources (Kinesis, Kafka, HTTP, RabbitMQ) and verify that messages are flowing through the Pulsar-based data processing pipeline.

## 1. Prerequisites

*   **Docker and Docker Compose:** Ensure Docker and Docker Compose are installed and operational on your system.
*   **Project Cloned:** You have cloned the project repository to your local machine.
*   **Sufficient Resources:** Your local Docker environment has adequate CPU, memory, and disk resources allocated to run all the services.

## 2. Setup Steps

1.  **Start Services:**
    Navigate to the root of the project and start all services using Docker Compose:
    ```bash
    docker-compose -f deploy/docker-compose.yml up -d
    ```

2.  **Wait for Services:**
    Allow a few minutes for all services to start and become healthy. You can monitor the status using `docker-compose ps` or by checking individual service logs (e.g., `docker-compose logs pulsar_service`). Pay special attention to `pulsar_service` and `localstack_service` to ensure they are fully initialized. Pulsar's health check, for instance, can take a couple of minutes.

3.  **Create Kinesis Stream (if testing Kinesis):**
    If you plan to test the Kinesis pipeline, create the necessary Kinesis stream in LocalStack. The default stream name used by the load tester and likely by the Kinesis source connector configuration is `my-kinesis-stream`.
    ```bash
    docker-compose -f deploy/docker-compose.yml exec localstack awslocal kinesis create-stream --stream-name my-kinesis-stream --shard-count 1 --region us-east-1
    ```
    *(Note: The region `us-east-1` should match your Kinesis source connector configuration and the `--kinesis-aws-region` parameter in the load test script, which defaults to `us-east-1`)*.

4.  **Ensure Other Resources (Kafka Topics, RabbitMQ Queues):**
    *   **Kafka:** The Pulsar Kafka source connector will read from a specific Kafka topic. Ensure this topic exists in the Kafka service, or that Kafka is configured for auto-topic creation. The load testing script will send messages to this topic.
    *   **RabbitMQ:** The Pulsar RabbitMQ source connector will read from a specific RabbitMQ queue. The load testing script declares the queue it sends to as durable, which should create it if it doesn't exist. Ensure the queue name used by the load tester matches the one configured in your RabbitMQ source connector.

    The `load_test.py` script uses default names like `kafka-topic-source` and `my-rabbitmq-queue`. **It's crucial to ensure these names align with the actual topics/queues your deployed Pulsar source connectors are configured to listen to.** Check your connector configuration files (e.g., `kafka-source-config.yaml`, `rabbitmq-source-config.yaml` used in your `pipeline.yaml`).

## 3. Running the Load Test

The load test script is located at `deployment/compose/scripts/load_test.py`. Run it from the root of the project.

**Note on Kafka Topic and RabbitMQ Queue:**
The example commands below use default values from `load_test.py`. **Verify and update `--kafka-topic` and `--rabbitmq-queue` to match the actual topic/queue your Pulsar source connectors are configured to consume from.**

*   **Kinesis:**
    ```bash
    python deployment/compose/scripts/load_test.py --connector kinesis --num-messages 10 \
      --kinesis-stream-name my-kinesis-stream \
      --kinesis-aws-endpoint-url http://localhost:4566 \
      --kinesis-aws-region us-east-1 
    ```

*   **Kafka:**
    *(Ensure `--kafka-topic` matches the topic your Pulsar Kafka source connector reads from.)*
    ```bash
    python deployment/compose/scripts/load_test.py --connector kafka --num-messages 10 \
      --kafka-brokers localhost:19092 \
      --kafka-topic kafka-topic-source 
    ```

*   **HTTP:**
    *(The default `--http-url http://localhost:10999` should match the listening address and port of your Pulsar HTTP source connector.)*
    ```bash
    python deployment/compose/scripts/load_test.py --connector http --num-messages 10 \
      --http-url http://localhost:10999
    ```

*   **RabbitMQ:**
    *(Ensure `--rabbitmq-queue` matches the queue your Pulsar RabbitMQ source connector reads from.)*
    ```bash
    python deployment/compose/scripts/load_test.py --connector rabbitmq --num-messages 10 \
      --rabbitmq-host localhost \
      --rabbitmq-port 5672 \
      --rabbitmq-username user \
      --rabbitmq-password password \
      --rabbitmq-queue my-rabbitmq-queue
    ```

## 4. Verification Methods

After running the load test, verify messages at different stages of the pipeline.

*   **A. Connector Logs (Pulsar Service):**
    Pulsar source connectors run within the Pulsar broker. Check the logs of the `pulsar_service` container for messages indicating connection to the source system (Kinesis, Kafka, etc.) and message ingestion.
    ```bash
    docker-compose logs pulsar_service
    ```
    Look for log entries specific to the connector you are testing (e.g., "KinesisSource", "KafkaSource", "HttpSource", "RabbitMQSource"). You should see messages about records being received and published to a "raw" topic.

*   **B. Translator Function Logs (Pulsar Service):**
    Translator functions (e.g., `ShipmentStatusTranslator`, `PaymentNoticeTranslator`) also run within the Pulsar broker process if deployed as built-in functions or via the generic function runtime.
    ```bash
    docker-compose logs pulsar_service
    ```
    Look for logs from your specific translator functions. Expect messages like "Successfully transformed message ID..." or similar, indicating successful processing. Note any errors if messages are failing transformation.

*   **C. Pulsar Raw Input Topics:**
    Source connectors typically publish raw, untransformed data to an input topic, often named like `raw-<source>-events` (e.g., `persistent://public/default/raw-kinesis-events`).
    Use `pulsar-client` (which can be run inside the `pulsar` container or locally if configured) to consume messages from these topics.
    ```bash
    docker-compose exec pulsar_service bin/pulsar-client consume persistent://public/default/raw-kinesis-events -s "sub-verify-raw" -n 0 -p Earliest
    # Replace 'raw-kinesis-events' with the appropriate topic for your source (e.g., raw-kafka-events, raw-http-events, raw-rabbitmq-events)
    # Check your pipeline.yaml or connector configs for the exact topic names.
    ```
    You should see the raw messages as sent by the load generator.

*   **D. Pulsar Common Events Topic:**
    Translator functions are expected to transform raw messages into a common format (`CommonEvent`) and publish them to a unified topic, typically `common-events`.
    ```bash
    docker-compose exec pulsar_service bin/pulsar-client consume persistent://public/default/common-events -s "sub-verify-common" -n 0 -p Earliest
    ```
    Messages here should be in the `CommonEvent` JSON structure, containing fields like `eventId`, `eventType`, `payload`, `source`, etc.

*   **E. Event Type Splitter Function Logs (Pulsar Service):**
    The `event-type-splitter` function consumes from `common-events` and routes messages to dynamic output topics. Check its logs for processing details.
    ```bash
    docker-compose logs pulsar_service
    ```
    Look for logs from `EventTypeSplitter` indicating which topic it's routing messages to based on `eventType`.

*   **F. Final Output Topics (Dynamic):**
    The `event-type-splitter` routes messages to topics based on the `CommonEvent.eventType` field. The exact naming convention for these topics depends on the splitter's implementation (e.g., it might be `persistent://<tenant>/<namespace>/<eventType>-events`).
    1.  **Check Splitter Logs:** The logs from the `event-type-splitter` (Step E) are the best place to confirm the output topics being used for specific event types.
    2.  **Consult Source Code:** If available, check the `EventTypeSplitter.java` (or similar) source code for its topic naming logic.
    3.  **Consume (Example):** If you know an `eventType` (e.g., `SHIPMENT_EVENT`) and the naming pattern (e.g., `persistent://acme/ingest/shipment-events`), you can try to consume from it:
        ```bash
        # Example for a hypothetical 'shipment-events' topic in 'acme/ingest' namespace
        docker-compose exec pulsar_service bin/pulsar-client consume persistent://acme/ingest/shipment-events -s "sub-verify-final" -n 0 -p Earliest
        ```
    Messages here should be the `CommonEvent` objects corresponding to the specific `eventType` handled by that topic.

## 5. Expected Message Transformation Flow

1.  **Load Generator:** Sends messages in a source-specific format (defined in `load_test.py`) to the target external system (Kinesis, Kafka, HTTP endpoint, RabbitMQ queue).
2.  **Pulsar Source Connector:** Ingests the raw message from the external system and publishes it to a Pulsar topic (e.g., `raw-kinesis-events`).
3.  **Translator Function:** Consumes the raw message, transforms it into the `CommonEvent` JSON structure, and publishes it to the `common-events` topic.
4.  **Event Type Splitter Function:** Consumes the `CommonEvent` from `common-events` and routes it to a specific Pulsar topic based on its `eventType` field.
5.  **Final Destination:** Messages reside in these event-type-specific topics, ready for consumption by downstream applications or further processing.

By checking logs and consuming messages at these various points, you can verify that the pipeline is operating as expected for each configured data source.
```
