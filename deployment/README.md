All deployment artefacts live here.

## Pipeline Overview

The data pipeline is designed to ingest data from a variety of sources, process it through a series of transformations, and then route it for further analysis or consumption.

*   **Ingestion:** The pipeline ingests data from seven distinct sources:
    1.  AWS Kinesis
    2.  Apache Kafka
    3.  Azure Event Hubs
    4.  RabbitMQ
    5.  HTTP (generic endpoint)
    6.  gRPC (generic endpoint)
    7.  Apache Pulsar (another Pulsar topic acting as a source)
*   **Translation:** Five specialized translator functions process the raw data from these sources. Each translator converts the source-specific format into a standardized `CommonEvent` JSON schema.
*   **Aggregation:** All translated `CommonEvent` messages are published to a central Pulsar topic named `common-events`.
*   **Routing:** An `EventTypeSplitter` function consumes messages from the `common-events` topic. It inspects the `eventType` field within each `CommonEvent` and dynamically routes the original message to different downstream Pulsar topics based on this type (e.g., `fn-split-user-profile-event`, `fn-split-order-event`).

The **single source of truth** for defining this entire pipeline, including all functions and connectors, their configurations, and their interconnections, is `deployment/pipeline.yaml`.

## Deployment Structure

This directory and its subdirectories contain all the necessary files and configurations for deploying the data pipeline.

*   `pipeline.yaml`: As described above, this file defines the entire pipeline structure.
*   `local-dev/`: Contains the setup for running a local development environment.
    *   `docker-compose.yml`: Defines the services needed for local development, including:
        *   Pulsar (standalone)
        *   Zookeeper (for Kafka)
        *   Kafka
        *   LocalStack (to emulate AWS Kinesis)
        *   RabbitMQ
        *   Note: For load testing, Azure Event Hubs, gRPC, and HTTP sources require external setup or specific client configurations. Azure Event Hubs is not emulated locally. For gRPC and HTTP, the `load-test.sh` script acts as the client.
    *   `bootstrap.sh`: A script intended to provision all Pulsar resources (tenants, namespaces, topics, functions, connectors) based on `pipeline.yaml`. See "Local Development Setup and Bootstrap Script" for more details, especially regarding potential manual setup.
    *   `scripts/load-test.sh`: A utility script to send sample data to all configured input sources of the pipeline.
*   `helm/`: Contains Helm chart templates used to generate various deployment manifests.
    *   `helm/templates/mesh/`: Holds templates for generating FunctionMesh Custom Resource Definitions (CRDs).
    *   `helm/templates/worker/`: Holds templates for generating Kubernetes Job manifests for a worker-style deployment (alternative to FunctionMesh).
    *   `helm/templates/compose/`: Holds the template for the `deployment/local-dev/bootstrap.sh` script (if generation is successful).
*   `build/deploy/` (Generated Directory): This directory is created by Gradle during the manifest generation process.
    *   It contains the Kubernetes manifest YAML files for FunctionMesh and worker-style deployments.

## Modifying the Pipeline

To add, remove, or change functions or connectors in the pipeline:

1.  **Edit `deployment/pipeline.yaml`**: Make your desired changes to the functions, connectors, or their configurations in this file.
2.  **Regenerate Manifests**: Run the Gradle task `./gradlew generateManifests`. This command will:
    *   Use the Helm templates in `deployment/helm/` and the definitions in `deployment/pipeline.yaml`.
    *   Generate/update the following files:
        *   `build/deploy/functionmesh-pipeline.yaml`
        *   `build/deploy/worker-pipeline.yaml`
        *   `deployment/local-dev/bootstrap.sh`
3.  **Local Development**: If you are running the pipeline locally using Docker Compose, simply running `./gradlew composeUp` is sufficient. This task has a dependency on `generateManifests` and will automatically regenerate all necessary artifacts before starting the environment.

## Generated Artifacts

The `generateManifests` task produces the following key artifacts:

*   **`build/deploy/functionmesh-pipeline.yaml`**:
    *   **Purpose**: Kubernetes manifest for deploying the entire pipeline using [FunctionMesh](https://functionmesh.io/).
    *   **Usage**: Apply this file to a Kubernetes cluster where FunctionMesh is installed (`kubectl apply -f build/deploy/functionmesh-pipeline.yaml`).

*   **`build/deploy/worker-pipeline.yaml`**:
    *   **Purpose**: Kubernetes manifest for deploying the pipeline as a series of Kubernetes Jobs. This is an alternative for environments where FunctionMesh is not available or desired. Each function/connector typically runs as a job that registers itself with the Pulsar cluster.
    *   **Usage**: Apply this file to a Kubernetes cluster (`kubectl apply -f build/deploy/worker-pipeline.yaml`).

*   **`deployment/local-dev/bootstrap.sh`**:
    *   **Purpose**: A shell script for the local Docker Compose environment. It should contain `pulsar-admin` commands to create and configure all functions and connectors defined in `pipeline.yaml` within the running Pulsar container.
    *   **Intended Usage**: This script is ideally generated by `./gradlew generateManifests` and then executed automatically by the `docker-compose.yml` setup when the Pulsar container is ready. It's also executed when you run `./gradlew composeUp` due to the `generateManifests` dependency.
    *   **Manual Intervention**: Due to issues with Helm-based generation (see below), you might need to manually create or verify this script.

## Local Development Setup and Bootstrap Script Issues

The `./gradlew generateManifests` Gradle task is designed to automate the generation of Kubernetes manifests and, crucially for local development, the `deployment/local-dev/bootstrap.sh` script using Helm. However, there are known issues with Helm (version v3.18.0 in the test environment) failing to correctly locate its `Chart.yaml` file when invoked via Gradle. This often prevents the `bootstrap.sh` script from being generated correctly.

**Impact on Local Development:**

If `bootstrap.sh` is not generated or is incomplete due to the Helm issue, the Pulsar pipeline (topics, functions, connectors) will **not** be automatically provisioned within the Dockerized Pulsar service started by `docker-compose up` or `./gradlew composeUp`.

**Workaround for `bootstrap.sh`:**

If `./gradlew generateManifests` fails or you suspect `bootstrap.sh` is incorrect, you will need to manually create or verify its contents. This script must contain the necessary `pulsar-admin` commands to set up your pipeline.

1.  **Tenant and Namespace Creation (if not using defaults):**
    Ensure your target tenant (e.g., `acme`) and namespace (e.g., `ingest`) exist.
    ```bash
    # Example:
    pulsar-admin tenants create acme --allowed-clusters standalone
    pulsar-admin namespaces create acme/ingest
    ```

2.  **Connector Deployment:**
    For each connector in `deployment/pipeline.yaml`, add a `pulsar-admin sources create` command.
    ```bash
    # Example for a Kinesis source connector:
    pulsar-admin sources create \
      --tenant acme \
      --namespace ingest \
      --name kinesis-input \
      --source-type kinesis \ # This might be 'kinesis' or a different type depending on the connector package
      --source-config-file "/pulsar/conf/connectors/kinesis-source-config.yaml" # This file defines the actual Kinesis settings AND the output Pulsar topic
      # --archive path/to/your/kinesis-connector.nar # Required if not a built-in type
      # Add other parameters like --parallelism as needed.
      # Note: The output topic (e.g., persistent://acme/ingest/raw-kinesis-events) is defined *inside* kinesis-source-config.yaml
    ```
    *   Refer to `pipeline.yaml` for connector names, the image used (which determines the `--source-type` or if it's a built-in type), and the `configRef` which points to the configuration that includes the target output topic. Ensure referenced config files are mounted or their content is inlined in `bootstrap.sh`.
    *   Consult the official Pulsar documentation for the exact `pulsar-admin sources create` syntax and available options for each connector type. The output topic for a source is specified within its configuration, not as a direct CLI parameter to `sources create`.

3.  **Function Deployment:**
    For each function in `deployment/pipeline.yaml`, add a `pulsar-admin functions create` command.
    ```bash
    # Example for a translator function:
    pulsar-admin functions create \
      --tenant acme \
      --namespace ingest \
      --name user-profile-translator \
      --jar "/pulsar/jars/translators.jar" \ # Ensure this path is where you mount/place the JAR
      --class-name com.example.pulsar.functions.transforms.translators.UserProfileTranslator \
      --inputs "persistent://acme/ingest/raw-azure-events,persistent://acme/ingest/raw-pulsar-events" \
      --output "persistent://acme/ingest/common-events" \
      --parallelism 1
      # Add other parameters like --user-config as needed
    ```
    *   Refer to `pipeline.yaml` for function names, JAR paths (ensure they are mounted), class names, input/output topics, and any other configurations.
    *   Consult the official Pulsar documentation for `pulsar-admin functions create` syntax.

**Using a Manual `bootstrap.sh`:**

*   Create/edit `deployment/local-dev/bootstrap.sh` with the necessary commands.
*   You will need to adjust `deployment/local-dev/docker-compose.yml` for the `pulsar` service to execute this script. This typically involves:
    *   Mounting your `bootstrap.sh` into the Pulsar container (e.g., to `/pulsar/bootstrap-local.sh`).
    *   Modifying the `command` or adding an `entrypoint` in the `pulsar` service definition to run this script after Pulsar starts. For example, by wrapping the original command:
        ```yaml
        # In docker-compose.yml, for the pulsar service:
        # volumes:
        #  - ./bootstrap.sh:/pulsar/bootstrap-local.sh # Mount the script
        # command: >
        #  sh -c "
        #    bin/pulsar standalone &
        #    # Wait for Pulsar to be ready (implement a robust check)
        #    until curl -s -f http://localhost:8080/admin/v2/brokers/health > /dev/null; do sleep 5; done;
        #    echo 'Pulsar ready, executing bootstrap-local.sh...';
        #    sh /pulsar/bootstrap-local.sh;
        #    wait # Keep the container running by waiting for the backgrounded Pulsar process
        #  "
        ```
    This revised command structure ensures Pulsar starts, becomes healthy, and then your custom script provisions the resources.

**Helm Manifest Generation for Kubernetes:**

If you encounter issues with the automated `generateManifests` task for Kubernetes manifests (`functionmesh-pipeline.yaml`, `worker-pipeline.yaml`):
*   **Temporary Workaround**: Manual Helm execution might be a temporary solution. Ensure Helm CLI is installed and functional in your direct shell environment. You can then attempt to generate manifests using commands similar to the following (from the project root directory):
    ```bash
    # For functionmesh-pipeline.yaml
    helm template pipeline-charts deployment/helm --values deployment/pipeline.yaml --show-only templates/mesh/function-mesh.yaml > build/deploy/functionmesh-pipeline.yaml

    # For worker-pipeline.yaml
    helm template pipeline-charts deployment/helm --values deployment/pipeline.yaml --show-only templates/worker/registration-job.yaml > build/deploy/worker-pipeline.yaml
    ```
    Remember to create the `build/deploy/` directory if it doesn't exist (`mkdir -p build/deploy`).

*   **Further Investigation**: The interaction between Gradle and Helm in this specific environment requires further investigation to fully resolve the automated `generateManifests` task.

## Load Testing (`deployment/local-dev/scripts/load-test.sh`)

A script named `load-test.sh` is provided in the `deployment/local-dev/scripts/` directory to facilitate sending sample data to all configured input sources of the pipeline.

**Purpose:**
*   To perform a basic end-to-end test of the data ingestion flow for each source.
*   To provide developers with a simple way to inject test messages into the pipeline running locally.

**Sources Targeted:**
The script attempts to send messages to:
1.  AWS Kinesis (via LocalStack)
2.  Apache Kafka
3.  Azure Event Hubs (placeholder, requires manual Azure setup/CLI)
4.  RabbitMQ
5.  HTTP endpoint
6.  gRPC endpoint (placeholder, requires `grpcurl` and service details)
7.  A Pulsar topic (`raw-pulsar-events`)

**Prerequisites:**
*   The local development environment (`docker-compose.yml`) should be up and running.
*   Pulsar should be ready and (ideally) all pipeline components (connectors, functions) provisioned via `bootstrap.sh`.
*   For Kinesis: AWS CLI installed and configured for LocalStack (e.g., `aws configure` with dummy credentials, default region `us-east-1`, and output format `json`). The script uses `http://localhost:4566` as the Kinesis endpoint.
*   For Kafka: Kafka command-line tools (like `kafka-console-producer.sh`) should be in your system's PATH.
*   For RabbitMQ: `rabbitmqadmin` CLI tool should be in your PATH (often comes with RabbitMQ management plugin).
*   `curl` for sending HTTP messages.
*   `pulsar-client` tool for sending messages directly to Pulsar.

It's often easiest to run this script from an environment where these client tools are readily available and configured, or from within a container that has these tools.

**How to Run:**
1.  Navigate to the script's directory:
    ```bash
    cd deployment/local-dev/scripts
    ```
2.  Make the script executable (if not already):
    ```bash
    chmod +x load-test.sh
    ```
3.  Execute the script:
    ```bash
    ./load-test.sh
    ```
The script will output information about which source it's targeting and the success/failure of message sending.
