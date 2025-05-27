All deployment artefacts live here.

## Deployment Structure

This directory and its subdirectories contain all the necessary files and configurations for deploying the data pipeline.

*   `pipeline.yaml`: This is the **single source of truth** for defining the entire pipeline, including all functions and connectors, their configurations, and their interconnections.
*   `local-dev/`: Contains the Docker Compose setup (`docker-compose.yml`) for running a local development environment. The `bootstrap.sh` script within this directory is auto-generated and used to provision the pipeline components inside Docker.
*   `helm/`: Contains Helm chart templates used to generate various deployment manifests.
    *   `helm/templates/mesh/`: Holds templates for generating FunctionMesh Custom Resource Definitions (CRDs).
    *   `helm/templates/worker/`: Holds templates for generating Kubernetes Job manifests for a worker-style deployment (alternative to FunctionMesh).
    *   `helm/templates/compose/`: Holds the template for the `deployment/local-dev/bootstrap.sh` script.
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
    *   **Purpose**: A shell script automatically generated for the local Docker Compose environment. It contains `pulsar-admin` commands to create and configure all functions and connectors defined in `pipeline.yaml` within the running Pulsar container.
    *   **Usage**: This script is executed automatically by the `docker-compose.yml` setup when the Pulsar container is ready. It's also executed when you run `./gradlew composeUp` due to the `generateManifests` dependency. You typically do not need to run this script manually.

## Known Issues with Manifest Generation

The `generateManifests` Gradle task is designed to automate the generation of Kubernetes manifests and the local `bootstrap.sh` script using Helm. However, in the current development/testing environment, there have been persistent issues with Helm (version v3.18.0) failing to correctly locate its `Chart.yaml` file when invoked via Gradle. This occurs even when various configurations are attempted, such as providing absolute paths for the chart and values files, and altering the working directory for the Helm execution.

The error messages, particularly from `helm lint`, often suggest that Helm is incorrectly changing its context or expecting `Chart.yaml` relative to the `templates/` subdirectory, which is not standard behavior. This prevents Helm from loading the chart correctly and thus failing the manifest generation.

As a result, running `./gradlew generateManifests` or any dependent tasks (like `./gradlew composeUp`) may fail due to this underlying Helm issue in the provided environment. The Helm templates themselves, located in `deployment/helm/templates/`, are believed to be logically correct for transforming `deployment/pipeline.yaml` into the desired manifests, based on manual review and previous successful partial renderings.

If you encounter issues with the automated `generateManifests` task:
*   **Temporary Workaround**: Manual Helm execution might be a temporary solution. Ensure Helm CLI is installed and functional in your direct shell environment. You can then attempt to generate manifests using commands similar to the following (from the project root directory):
    ```bash
    # For functionmesh-pipeline.yaml
    helm template pipeline-charts deployment/helm --values deployment/pipeline.yaml --show-only templates/mesh/function-mesh.yaml > build/deploy/functionmesh-pipeline.yaml

    # For worker-pipeline.yaml
    helm template pipeline-charts deployment/helm --values deployment/pipeline.yaml --show-only templates/worker/registration-job.yaml > build/deploy/worker-pipeline.yaml

    # For bootstrap.sh (using the render-bootstrap.yaml wrapper)
    helm template pipeline-charts deployment/helm --values deployment/pipeline.yaml --show-only templates/compose/render-bootstrap.yaml > deployment/local-dev/bootstrap.sh
    ```
    Remember to create the `build/deploy/` directory if it doesn't exist (`mkdir -p build/deploy`).

*   **Further Investigation**: The interaction between Gradle and Helm in this specific environment requires further investigation to fully resolve the automated `generateManifests` task. The issue seems to stem from how Helm perceives paths or its context when called from a Gradle `exec` block.
