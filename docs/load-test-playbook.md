# Load Testing Playbook

This document outlines the tools and procedures for conducting load testing in this project.

## Overview

Load testing is crucial for understanding the performance characteristics and breaking points of our application's various components and I/O connectors. We currently support two methods for load testing:

1.  **k6-based Load Testing (Recommended)**: Utilizes [k6](https://k6.io/), a modern, developer-centric load testing tool, for flexible and powerful test execution. This is the recommended approach for new tests.
2.  **Python-based Load Testing**: The original load testing script (`deployment/compose/scripts/load_test.py`) written in Python.

## k6-based Load Testing

This approach uses k6 scripts orchestrated by a Python wrapper. The k6 scripts are located in `deployment/compose/k6/`.

### 1. Installing k6

k6 is a command-line tool. You'll need to install it on the machine from which you intend to run the tests.

*   **Linux (Debian/Ubuntu):**
    ```bash
    sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57DF758E76A2D530910
    echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" | sudo tee /etc/apt/sources.list.d/k6.list
    sudo apt-get update
    sudo apt-get install k6
    ```
*   **Linux (Fedora/CentOS):**
    Refer to the [official k6 documentation](https://k6.io/docs/getting-started/installation/).
*   **macOS (using Homebrew):**
    ```bash
    brew install k6
    ```
*   **Windows (using Chocolatey or Winget):**
    ```bash
    # Using Chocolatey
    choco install k6

    # Using Winget
    winget install k6
    ```
*   **Docker:**
    k6 also provides official Docker images:
    ```bash
    docker pull grafana/k6
    ```
    If using the Docker image directly, you'll need to adapt the `run_k6_load_test.py` script or mount your local k6 scripts and workspace into the container. The orchestrator script assumes a local `k6` executable by default.

For the most up-to-date installation instructions and other operating systems, please consult the [official k6 installation guide](https://k6.io/docs/getting-started/installation/).

### 2. Running k6 Tests with `run_k6_load_test.py`

The primary way to execute k6 tests is via the `deployment/compose/scripts/run_k6_load_test.py` script. This script handles resource setup (like creating Kafka topics or Kinesis streams if needed) and then invokes the appropriate k6 JavaScript test.

**Basic Usage:**

```bash
cd deployment/compose/scripts
python ./run_k6_load_test.py --connector <connector_type> [options...]
```

**Key Arguments for `run_k6_load_test.py`:**

*   `-c, --connector`: (Required) Specifies the connector to test. Choices: `http`, `kafka`, `rabbitmq`, `kinesis`, `pulsar-function`.
*   `--k6-executable`: Path to the k6 executable. Defaults to `k6`. **Important for custom builds.**
*   `--k6-vus`: Number of Virtual Users (VUs) for k6. Default: `1`.
*   `--k6-duration`: Test duration for k6 (e.g., `30s`, `2m`). Default: `10s`.
*   `--k6-sleep`: Sleep duration in seconds between iterations for k6 VUs. Default: `1.0`.
*   `--verbose, -v`: Enable verbose logging for the Python orchestrator script.

**Connector-Specific Arguments:**

*   **HTTP:**
    *   `--http-url`: Target URL (e.g., `http://localhost:10999/ingest`).
*   **Kafka:**
    *   `--kafka-brokers`: Comma-separated list of Kafka brokers (e.g., `localhost:19092`).
    *   `--kafka-topic`: Target Kafka topic.
*   **RabbitMQ:**
    *   `--rabbitmq-url`: Full AMQP URL (e.g., `amqp://user:password@localhost:5672/`).
    *   `--rabbitmq-queue`: Target RabbitMQ queue name.
*   **Kinesis:**
    *   `--kinesis-stream-name`: Target Kinesis stream name.
    *   `--kinesis-aws-endpoint-url`: Optional. Endpoint URL for Kinesis (e.g., `http://localhost:4566` for LocalStack).
    *   `--kinesis-aws-region`: AWS region.
    *   `--kinesis-aws-access-key-id`: AWS Access Key ID.
    *   `--kinesis-aws-secret-access-key`: AWS Secret Access Key.

#### Testing Specific Pulsar Functions (`--connector pulsar-function`)

Beyond testing I/O connectors, `run_k6_load_test.py` can also orchestrate load tests against specific Pulsar functions defined in your pipeline (e.g., from `deployment/pipeline.yaml`). This mode uses k6 scripts designed to produce messages to a function's input Pulsar topic and consume from its output Pulsar topic.

**Key Arguments for Pulsar Function Testing:**

When using `-c pulsar-function` or `--connector pulsar-function`, the following arguments are relevant:

*   `--pulsar-script SCRIPT_NAME`: (Required) Specifies the name of the k6 JavaScript file located in `deployment/compose/k6/pipeline_functions/` to execute (e.g., `payment_notice_translator_test.js`).
*   `--pulsar-url URL`: Pulsar service URL (e.g., `pulsar://localhost:6650`). Default: `pulsar://localhost:6650`.
*   `--pulsar-input-topic TOPIC_URI`: (Required) The full Pulsar topic URI for the function's input (e.g., `persistent://public/default/my-input-topic`).
*   `--pulsar-output-topic TOPIC_URI`: (Required) The full Pulsar topic URI for the function's output (e.g., `persistent://public/default/my-output-topic`).
*   `--pulsar-send-timeout-ms MS`: Pulsar producer send timeout in milliseconds. Default: `3000`.
*   `--pulsar-receive-timeout-ms MS`: Pulsar consumer receive timeout in milliseconds. Default: `1000`.
*   `--pulsar-ack-timeout-ms MS`: Pulsar consumer acknowledgment timeout in milliseconds. Default: `30000`.
*   `--pulsar-receiver-queue-size COUNT`: Pulsar consumer receiver queue size. Default: `100`.

**Important k6 Build Requirement & API Note for Pulsar Tests:**

*   Testing Pulsar functions **requires** a custom k6 binary. Unlike some other connectors (like Kafka or AMQP for RabbitMQ), Grafana **does not currently provide an official `xk6-pulsar` extension.**
*   You will need to:
    1.  **Find a community-provided `xk6-pulsar` extension** (e.g., by searching on GitHub).
    2.  **Evaluate its suitability and security** for your needs.
    3.  **Build a custom k6 binary** incorporating this extension using the `xk6` tool.
*   The k6 scripts provided in `deployment/compose/k6/pipeline_functions/` (e.g., `payment_notice_translator_test.js`) are templates that use an **assumed JavaScript API** for interacting with Pulsar.
*   **You will very likely need to modify these Pulsar k6 scripts** to match the specific API of the community `xk6-pulsar` extension you choose.
*   For more detailed information on this critical prerequisite, including how to potentially build an extension and the nature of the API assumption, **please carefully read `deployment/compose/k6/pipeline_functions/README.md` before attempting to run Pulsar function tests.**

Once you have your custom `k6` executable (e.g., named `k6-pulsar`), you will use the `--k6-executable ./k6-pulsar` argument with `run_k6_load_test.py` to point to it.

**Example for Pulsar Function Test:**

```bash
# Assuming you have a custom k6 build at ./k6-pulsar
cd deployment/compose/scripts

python ./run_k6_load_test.py \
    --connector pulsar-function \
    --k6-executable ./k6-pulsar \
    --pulsar-script payment_notice_translator_test.js \
    --pulsar-url pulsar://localhost:6650 \
    --pulsar-input-topic persistent://acme/ingest/raw-kafka-events \
    --pulsar-output-topic persistent://acme/ingest/common-events \
    --k6-vus 10 \
    --k6-duration 30s
```

The k6 JavaScript files for these tests are located in `deployment/compose/k6/pipeline_functions/`. Consult the README in that directory for more specifics on the scripts and the required Pulsar extension.

**Example (Original I/O Connector Test):**

```bash
cd deployment/compose/scripts
python ./run_k6_load_test.py --connector http --http-url http://my-service/api --k6-vus 50 --k6-duration 1m
```

### 3. Custom k6 Builds (CRITICAL for Kafka, RabbitMQ, Kinesis, and Pulsar Functions)

The standard k6 binary only supports HTTP(S) and WebSocket tests out-of-the-box. To test **Kafka, RabbitMQ, Kinesis, or Pulsar functions**, you **must** use a k6 binary that has been custom-built with the necessary `xk6` extensions.

Detailed instructions for building k6 with I/O connector extensions are in `deployment/compose/k6/README.md`. For Pulsar function testing, refer to `deployment/compose/k6/pipeline_functions/README.md` for specifics on the `xk6-pulsar` extension.

Once you have your custom `k6` executable, you can tell `run_k6_load_test.py` to use it:

```bash
# For I/O connectors (e.g., Kafka)
python ./run_k6_load_test.py --connector kafka --k6-executable /path/to/your/custom/k6-kafka ...

# For Pulsar functions
python ./run_k6_load_test.py --connector pulsar-function --k6-executable /path/to/your/custom/k6-pulsar ...
```

If your custom k6 binary is in your system's PATH and named `k6`, the script might pick it up, but it's safer to specify the path if you have multiple k6 versions.

### 4. k6 Test Scripts and Customization

The actual k6 test logic is written in JavaScript files located in `deployment/compose/k6/`:
*   `http_test.js`
*   `kafka_test.js`
*   `rabbitmq_test.js`
*   `kinesis_test.js`
*   `common.js` (shared payload generation logic for I/O connector tests)

For Pulsar function tests, scripts are located in `deployment/compose/k6/pipeline_functions/` (e.g., `payment_notice_translator_test.js`). These also use `../common.js` if applicable and are configured via environment variables set by `run_k6_load_test.py`.

You can customize these scripts further if needed (e.g., change payload structure, add more complex test scenarios, or adjust k6 options and thresholds). Refer to the [k6 documentation](https://k6.io/docs/) for details on writing k6 scripts.

### 5. Test Output and Results

k6 outputs detailed metrics and a summary to the console at the end of each test run. This includes information on request counts, error rates, response times, RPS (requests per second), etc.

For more advanced visualization and analysis, k6 can output metrics to various backends like Prometheus, Grafana, Datadog, New Relic, etc. Setting up such integrations is currently beyond the scope of this playbook but is a common practice for ongoing performance monitoring.

### 6. Verifying Message Delivery

While k6 will report on the success of its operations (e.g., HTTP status codes, Kafka publish acknowledgments if configured in detail), it's good practice to independently verify that messages are indeed reaching their destinations, especially during initial setup or when debugging. Here are some general strategies:

*   **HTTP:**
    *   Check the server logs of your target HTTP service for incoming requests and successful processing.
    *   If the service has metrics, monitor them for an increase in request counts.
    *   For testing, you could temporarily point the HTTP test to a request inspector tool (like RequestBin or a custom echo server) to see the payloads.

*   **Kafka:**
    *   Use a command-line Kafka consumer to subscribe to the target topic and observe the messages. For example, using `kafka-console-consumer.sh` (comes with Kafka):
        ```bash
        # Replace with your Kafka broker(s) and topic
        kafka-console-consumer.sh --bootstrap-server <your-kafka-brokers> --topic <your-kafka-topic> --from-beginning
        ```
    *   If you have a UI for Kafka (like Conduktor, Offset Explorer, or custom dashboards), use it to inspect topic contents and consumer group lag.

*   **RabbitMQ:**
    *   Use the RabbitMQ Management Plugin (if enabled) to view queue depths, message rates, and inspect messages in the queue.
    *   You can also use command-line tools like `rabbitmqadmin` or client libraries in a separate script to consume messages from the target queue for verification.
        ```bash
        # Example using rabbitmqadmin (if installed and configured)
        rabbitmqadmin get queue=<your-queue-name> requeue=false count=10
        ```

*   **Kinesis:**
    *   Use the AWS CLI to get records from the Kinesis stream. This can be done by getting a shard iterator and then using that iterator to get records.
        ```bash
        # 1. List shards to get a shard ID
        aws kinesis list-shards --stream-name <your-kinesis-stream-name> --endpoint-url <localstack_or_aws_endpoint_if_not_default> --region <your-region>
        # 2. Get a shard iterator (replace SHARD_ID)
        aws kinesis get-shard-iterator --stream-name <your-kinesis-stream-name> --shard-id <shard-id-from-above> --shard-iterator-type TRIM_HORIZON --endpoint-url <localstack_or_aws_endpoint_if_not_default> --region <your-region>
        # 3. Get records (replace SHARD_ITERATOR)
        aws kinesis get-records --shard-iterator <shard-iterator-from-above> --endpoint-url <localstack_or_aws_endpoint_if_not_default> --region <your-region>
        ```
    *   If messages are processed by a downstream AWS Lambda or other services, check their logs (e.g., CloudWatch Logs).

These methods provide a way to confirm that the load generated by k6 is correctly interacting with the target systems. Remember to replace placeholders with your actual service details and adjust commands based on your specific setup (e.g., LocalStack endpoint URLs, credentials).

## Python-based Load Testing (Legacy)

The original Python-based load testing script is `deployment/compose/scripts/load_test.py`. This script sends messages directly using Python libraries.

**Usage:**

```bash
cd deployment/compose/scripts
python ./load_test.py --connector <connector_type> --num-messages <count> [options...]
```

This script remains available if needed, but for new and more complex load testing scenarios, the k6-based approach is recommended due to k6's advanced features, performance, and community support. Refer to its command-line help for detailed options: `python ./load_test.py --help`.

---

This playbook should help you get started with load testing in the project. Remember to consult the respective tool documentation (k6, Python libraries) for more in-depth information.
