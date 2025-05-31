# k6 Scripts for Pipeline Function Load Testing

This directory contains k6 scripts designed to load test specific functions within the data pipeline, typically [Apache Pulsar Functions](https://pulsar.apache.org/docs/functions-overview/).

## IMPORTANT: `xk6-pulsar` Extension Prerequisite

**Status of `xk6-pulsar`:**

*   As of this writing, **Grafana does not provide an official, pre-built `xk6-pulsar` extension** in the same way it does for Kafka or AMQP.
*   To test Pulsar functions using k6, you **must** find a community-provided Pulsar extension for k6, or develop your own.

**Building with a Community Extension:**

1.  **Find an Extension**: You will need to search for a suitable community extension on platforms like GitHub. One example that appears in searches is `github.com/automatingeverything/xk6-pulsar`.
    *   **Disclaimer**: This specific extension is provided as an example found during a search. **It has not been vetted for functionality, security, or maintenance status.** You are responsible for evaluating any community extension you choose.
2.  **Build k6**: Once you've chosen an extension, you'll use the `xk6` tool to build a custom k6 binary:
    ```bash
    # Example using the Go toolchain and xk6:
    # 1. Install xk6 if you haven't already:
    #    go install go.k6.io/xk6/cmd/xk6@latest
    # 2. Build k6 with the chosen Pulsar extension:
    xk6 build --with github.com/path/to/chosen/xk6-pulsar@version
    ```
    Replace `github.com/path/to/chosen/xk6-pulsar@version` with the actual import path and desired version/commit of the extension you select. This will produce a custom `k6` (or `k6.exe`) binary.

## Script Design and API Assumption

The example script provided in this directory (`payment_notice_translator_test.js`) is intended as a template.

*   **Assumed API**: The JavaScript API used in the example script (e.g., `pulsar.connect()`, `producer.send()`, `consumer.receive()`) is **assumed** based on common patterns in other xk6 messaging extensions.
*   **User Modification Required**: It is **highly likely** that you will need to **modify `payment_notice_translator_test.js` (and any other Pulsar scripts you create based on it)** to match the specific JavaScript API exposed by the actual `xk6-pulsar` community extension you choose to build and use. Always refer to the documentation of the specific extension for correct API usage.

## Orchestration

These scripts are intended to be run by an orchestrator like `../../scripts/run_k6_load_test.py`. The orchestrator needs to be configured to use your custom-built k6 binary (via the `--k6-executable` flag) and pass the necessary environment variables for Pulsar connection and test parameters.
