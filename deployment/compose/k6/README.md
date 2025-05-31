# k6 Load Testing Scripts

These scripts are designed to be run with [k6](https://k6.io/). They are orchestrated by `../scripts/run_k6_load_test.py`.

## Environment Variables for Configuration

The k6 scripts in this directory (e.g., `http_test.js`, `kafka_test.js`, etc.) are typically configured using environment variables. These variables are set by the `run_k6_load_test.py` orchestration script based on its command-line arguments.

Common environment variables include:
*   `K6_VUS`: Number of virtual users.
*   `K6_DURATION`: Test duration (e.g., `30s`, `1m`).
*   `K6_SLEEP`: Sleep duration between iterations.
*   Connector-specific variables like `HTTP_URL`, `KAFKA_BROKERS`, `KAFKA_TOPIC`, `RABBITMQ_URL`, `RABBITMQ_QUEUE`, `KINESIS_STREAM_NAME`, `AWS_KINESIS_ENDPOINT_URL`, `AWS_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`.

Refer to the `run_k6_load_test.py` script and individual k6 test scripts for details on specific environment variables used.

## Custom k6 Builds for Certain Connectors

To run tests for Kafka, RabbitMQ, Kinesis, or other protocols not built into the standard k6, you need a k6 binary that includes the necessary extensions. This is achieved using `xk6`.

### Building k6 with Extensions (xk6)

To add functionalities not built into the standard k6 (like specific protocols for Kafka, AMQP, AWS services, or Pulsar), you need to compile a custom k6 binary using `xk6`.

**Prerequisites:**

1.  **Go Environment**: Ensure you have a working Go installation (version 1.19 or newer is typically recommended by k6). You can check by running `go version`.
2.  **xk6 Tool**: Install `xk6`, the k6 custom builder:
    ```bash
    go install go.k6.io/xk6/cmd/xk6@latest
    ```
    Make sure your Go binary path (`$GOPATH/bin` or `$HOME/go/bin`) is in your system's `PATH` to run `xk6` directly.

**Building Process:**

1.  **Navigate to a Directory**: Go to any directory where you want to build your custom k6 binary. The binary will be created in that directory.
2.  **Build Command**: Use the `xk6 build` command, specifying the extensions you need with the `--with` flag. The typical format is:
    ```bash
    xk6 build --with GITHUB_IMPORT_PATH_OF_EXTENSION[@VERSION_OR_BRANCH]
    ```
    For example:
    ```bash
    xk6 build --with github.com/grafana/xk6-kafka@latest
    ```
    This command downloads the extension's source code and compiles it along with k6 into a new executable file named `k6` (or `k6.exe` on Windows) in your current directory.

3.  **Multiple Extensions**: If you need multiple extensions (e.g., for testing Kafka and Prometheus simultaneously), you can include multiple `--with` flags:
    ```bash
    xk6 build \
        --with github.com/grafana/xk6-kafka@latest \
        --with github.com/grafana/xk6-output-prometheus-remote@latest
    ```

**Examples for This Project's Connectors:**

The following commands build k6 with extensions used by the I/O connector test scripts in this directory:

*   **For Kafka:**
    ```bash
    xk6 build --with github.com/mostafa/xk6-kafka@latest
    ```
    *(Note: `github.com/mostafa/xk6-kafka` is a community extension. Grafana also offers `github.com/grafana/xk6-kafka` which might have a different API or features. Choose the one that best fits your needs and the example scripts.)*

*   **For RabbitMQ (AMQP):**
    ```bash
    xk6 build --with github.com/grafana/xk6-amqp@latest
    ```

*   **For Kinesis (via `xk6-aws`):**
    ```bash
    xk6 build --with github.com/grafana/xk6-aws@latest
    ```
    *Note on `xk6-aws`*: This extension provides access to various AWS services. Ensure the version you use supports Kinesis as expected by the scripts. The `xk6-aws` extension might be modular; consult its documentation if you need to specify submodules (e.g., `kinesis=github.com/grafana/xk6-aws/kinesis`) or if Kinesis support is included by default.

After building, use this custom `k6` binary. The `run_k6_load_test.py` orchestrator script can be pointed to this custom binary using the `--k6-executable` argument.

## Pulsar Function Testing

For testing Pulsar functions, which also requires a custom k6 build with an `xk6-pulsar` extension, refer to the specific README in the `pipeline_functions/` subdirectory:
- `pipeline_functions/README.md`

That README provides details on the challenges of finding an `xk6-pulsar` extension and the need to potentially adapt the example Pulsar k6 scripts to the chosen extension's API.
```
