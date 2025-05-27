# Deployment Overview

All deployment artefacts for the Pulsar pipeline reside in this directory. This document outlines the structure, generation process, and usage of these artefacts.

## Pipeline Definition

The overall pipeline structure, its functions, and global configurations like the Pulsar `tenant` and `namespace` are primarily defined in `deployment/pipeline.yaml`.

Connectors (Sources and Sinks) are defined in their respective subdirectories within the `connectors/` directory at the root of the project. Each connector is specified by a `connector.yaml` file and its associated configuration files (e.g., `config.dev.yaml`, `config.prod.yaml`). The `generateManifests` task automatically discovers these connectors. For details on the schema of `connector.yaml`, please refer to the main `README.md` at the project root.

## Deployment Structure

*   `pipeline.yaml`: Defines functions and global pipeline settings (tenant, namespace).
*   `connectors/` (Root Directory): Contains subdirectories for each connector, each with its `connector.yaml` and specific configuration files.
*   `local-dev/`: Contains the setup for running a local development environment.
    *   `docker-compose.yml`: Defines services for local development (Pulsar, Zookeeper, Kafka, LocalStack, RabbitMQ).
    *   `bootstrap.sh`: **Generated script** to provision all Pulsar resources (tenant, namespace, topics, functions, connectors) based on `pipeline.yaml` and discovered connectors.
    *   `scripts/load-test.sh`: Utility script to send sample data to pipeline input sources.
*   `helm/`: Contains Helm chart templates used by the `generateManifests` task.
    *   `helm/templates/mesh/`: Templates for FunctionMesh Custom Resource Definitions (CRDs).
    *   `helm/templates/worker/`: Templates for Kubernetes Job manifests (worker-style deployment).
    *   `helm/templates/compose/`: Template for the `deployment/local-dev/bootstrap.sh` script.
*   `build/tmp/helm_values.yaml` (Generated File): Intermediate file created by `generateManifests` containing aggregated values from `pipeline.yaml` and `connectors/` to feed into Helm.
*   `build/deploy/` (Generated Directory): Output directory for Kubernetes manifests.
    *   `functionmesh-pipeline.yaml`: Kubernetes manifest for FunctionMesh deployment.
    *   `worker-pipeline.yaml`: Kubernetes manifest for worker-style (Kubernetes Job) deployment.

## `generateManifests` Gradle Task

The `./gradlew generateManifests` task is the core mechanism for generating all deployment artifacts. It performs the following steps:

1.  Reads `deployment/pipeline.yaml` to get function definitions and global values (tenant, namespace).
2.  Scans the `connectors/` directory at the project root to discover all defined IO connectors (sources/sinks), reading their `connector.yaml` and associated configuration files.
3.  Consolidates all this information into a temporary `build/tmp/helm_values.yaml` file.
4.  Uses Helm with the templates in `deployment/helm/` and the generated `helm_values.yaml` to produce three key artifacts:
    *   `build/deploy/functionmesh-pipeline.yaml`: For deploying to Kubernetes using FunctionMesh.
    *   `build/deploy/worker-pipeline.yaml`: For deploying to Kubernetes using a worker/job-based approach.
    *   `deployment/local-dev/bootstrap.sh`: A shell script for setting up the pipeline in the local Docker Compose environment.
5.  Makes the generated `deployment/local-dev/bootstrap.sh` script executable.

This task ensures that all deployment manifests are consistently generated from the pipeline definitions.

## Local Development: `bootstrap.sh` and Docker Compose Setup

The `deployment/local-dev/bootstrap.sh` script is crucial for the local Docker Compose environment. It is **generated automatically** by the `./gradlew generateManifests` task. When executed (typically by the `docker-compose.yml` setup for the Pulsar service), it uses `pulsar-admin` commands to:

*   Create the configured Pulsar tenant and namespace.
*   Deploy all functions defined in `pipeline.yaml`.
*   Deploy all connectors discovered from the `connectors/` directory.

This script automates the entire pipeline setup within the local Pulsar container.

### Docker Compose Volume Mounts for `bootstrap.sh`

For `bootstrap.sh` to correctly deploy functions and custom connectors, their packaged files (JARs/NARs) and connector configuration files must be mounted into the Pulsar container at specific paths that the script expects. The `deployment/helm/templates/compose/bootstrap.sh.tpl` template defines these expected paths.

**Ensure your `deployment/local-dev/docker-compose.yml` for the `pulsar` service includes the following volume mounts:**

1.  **Function JARs:**
    *   **Source (Your Project):** `../functions/<function-name>/build/libs/<function-jar-name>.jar` (or wherever your function JAR is built)
    *   **Target (Pulsar Container):** `/pulsar/functions/<function-jar-name>.jar`
        *   *Example:* `- ../functions/my-processor/build/libs/my-processor-0.1.0.jar:/pulsar/functions/my-processor-0.1.0.jar`
    *   The `bootstrap.sh.tpl` uses `{{ $func.jar }}` if present in `pipeline.yaml` (referring to the filename), or defaults to `/pulsar/functions/{{ $name }}.jar` if only `image` is specified (assuming a naming convention for local testing).

2.  **Custom Connector Archives (NARs/JARs):**
    *   **Source (Your Project):** `../connectors/<connector-id>/build/libs/<connector-archive-name>.nar` (or `.jar`)
    *   **Target (Pulsar Container):** `/pulsar/connectors/<connector-archive-name>.nar` (or `.jar`)
        *   *Example:* `- ../connectors/custom-kinesis-source/build/libs/custom-kinesis-connector.nar:/pulsar/connectors/custom-kinesis-connector.nar`
    *   The `bootstrap.sh.tpl` uses `{{ $conn.archive }}` if present in `connector.yaml` (referring to the filename), or defaults to `/pulsar/connectors/{{ $name }}.nar` if only `image` is specified for a custom connector (assuming a naming convention).

3.  **Connector Configuration Files:**
    *   **Source (Your Project):** `../connectors/<connector-id>/<your-config-file.yaml>` (e.g., `config.dev.yaml`)
    *   **Target (Pulsar Container):** `/pulsar/connectors/<connector-id>_config.yml`
        *   *Example:* `- ../connectors/kafka-source-01/config.dev.yaml:/pulsar/connectors/kafka-source-01_config.yml`
    *   The `bootstrap.sh.tpl` generates the `--source-config-file` path as `/pulsar/connectors/{{ $name }}_config.yml` (where `$name` is the connector ID). The mounted configuration file name inside the container **must match this pattern**.

**Note:** The exact source paths for JARs/NARs depend on your subproject build configurations. The target paths are conventions established by `bootstrap.sh.tpl`.

## Modifying the Pipeline

To add, remove, or change components in the pipeline:

1.  **For Functions:**
    *   Edit `deployment/pipeline.yaml` to add, remove, or modify function definitions and their configurations.
    *   Ensure the function's JAR file is correctly built and its path is accurately reflected for local development volume mounts if using `bootstrap.sh`.
2.  **For Connectors:**
    *   Add or remove subdirectories within the `connectors/` directory at the project root.
    *   To add a new connector: create a new directory (e.g., `connectors/new-connector/`), add `connector.yaml`, and its specific configuration file(s) (e.g., `config.dev.yaml`).
    *   To remove a connector: delete its subdirectory from `connectors/`.
    *   To modify a connector: edit its `connector.yaml` or associated configuration files within its directory. Ensure custom connector NAR/JAR files are built and configuration file paths are correct for local development mounts.
3.  **Regenerate Manifests**: Run the Gradle task:
    ```bash
    ./gradlew generateManifests
    ```
    This command will update `build/deploy/functionmesh-pipeline.yaml`, `build/deploy/worker-pipeline.yaml`, and `deployment/local-dev/bootstrap.sh` based on your changes.
4.  **Local Development**: If running locally via Docker Compose, `./gradlew composeUp` will automatically run `generateManifests` before starting the environment. If the environment is already running, you might need to restart it (`./gradlew composeDown && ./gradlew composeUp`) for `bootstrap.sh` changes to take effect, or manually execute relevant parts of `bootstrap.sh` inside the Pulsar container.

## Generated Kubernetes Manifests

The `generateManifests` task produces the following Kubernetes deployment artifacts:

*   **`build/deploy/functionmesh-pipeline.yaml`**:
    *   **Purpose**: Kubernetes manifest for deploying the entire pipeline using [FunctionMesh](https://functionmesh.io/). It defines `Function`, `Source`, and `Sink` Custom Resources.
    *   **Usage**: Apply this file to a Kubernetes cluster where FunctionMesh is installed (`kubectl apply -f build/deploy/functionmesh-pipeline.yaml`).

*   **`build/deploy/worker-pipeline.yaml`**:
    *   **Purpose**: Kubernetes manifest for deploying the pipeline as a series of Kubernetes Jobs. This is an alternative for environments where FunctionMesh is not available. Each job typically uses `pulsar-admin` to register a function or connector with the Pulsar cluster.
    *   **Usage**: Apply this file to a Kubernetes cluster (`kubectl apply -f build/deploy/worker-pipeline.yaml`).

## Load Testing (`deployment/local-dev/scripts/load-test.sh`)

A script named `load-test.sh` is provided in `deployment/local-dev/scripts/` to send sample data to configured input sources. This helps in performing basic end-to-end tests.

**Prerequisites:**
*   Local development environment (`docker-compose.yml`) is up and running.
*   Pipeline components (connectors, functions) are provisioned via `bootstrap.sh`.
*   Necessary client tools (AWS CLI for LocalStack, Kafka tools, `rabbitmqadmin`, `curl`, `pulsar-client`) are installed and configured in the environment where you run the script.

**How to Run:**
1.  Navigate to the script's directory: `cd deployment/local-dev/scripts`
2.  Make it executable: `chmod +x load-test.sh`
3.  Run: `./load-test.sh`

The script will output information about its progress.
