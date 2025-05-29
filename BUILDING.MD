# Building the Project

This document provides instructions for building the project, its subprojects, and understanding the artifact generation process.

## Prerequisites
- Git (for cloning the repository)
- Gradle Wrapper (provided with the project)

## Using the Gradle Wrapper
The project uses the Gradle Wrapper to ensure a consistent build environment. Always use `./gradlew` (on Linux/macOS) or `gradlew.bat` (on Windows) for executing Gradle tasks.

Examples:
- To build all modules: `./gradlew build`
- To clean the project: `./gradlew clean`

## Java Version
This project is configured to use Java 17. Ensure your environment is compatible, or rely on the Gradle toolchain feature to manage the JDK.

## Common Gradle Tasks
- `build`: Compiles, tests, and assembles all modules.
- `clean`: Deletes build artifacts.
- `test`: Runs unit tests for all modules.
- `integrationTest`: Runs integration tests for all modules (if configured).
- `spotlessCheck`: Checks code formatting.
- `spotlessApply`: Applies code formatting.

## Working with Specific Subprojects
You can run Gradle tasks for specific subprojects. For example:
- To build only the `common` module: `./gradlew :common:build`
- To run tests for the `user-profile-translator` function: `./gradlew :functions:translators:user-profile-translator:test`

## Artifact Generation
- **Lean JARs:** Some older function modules or certain utility modules may still be configured to produce lean JAR files. These JARs contain only the module's compiled code and resources, without bundling common dependencies like Pulsar APIs or test frameworks.
- **NAR files:** 
  - All translator functions (e.g., `shipment-status-translator`, `user-profile-translator`, etc., under `functions/translators/`) are now configured to produce **lean NAR (Pulsar Archive) files**. These NARs (e.g., `shipment-status-translator.nar`, with no version in the filename) bundle the function's compiled code and only its essential runtime dependencies (like Jackson). They specifically exclude Pulsar APIs, logging frameworks, and test dependencies, which are provided by the Pulsar runtime. This makes them the standard packaging format for deploying these functions.
  - Similarly, custom connectors built from source within this repository (e.g., the `connectors/grpc` project) are now also configured to produce version-less, lean NAR files (e.g., `grpc.nar`). These also exclude Pulsar-provided libraries and include only their essential runtime dependencies.
  - Other modules like `functions:splitter` may still produce NARs, potentially with different dependency bundling characteristics.
- **Container Images (Jib):** Subprojects intended for containerization (like functions and connectors) are typically configured with the Google Jib plugin. You can build container images directly using tasks like:
  - `./gradlew :<subproject-path>:jibDockerBuild` (builds to local Docker daemon)
  - `./gradlew :<subproject-path>:jib` (builds and pushes to a configured remote registry)

## Bundling Artifacts for Deployment

The project includes a special Gradle task `bundleForDeploy` designed to collect all necessary artifacts for various deployment scenarios.

To run this task:
```bash
./gradlew bundleForDeploy
```

### What it Does:
-   **Collects Lean Artifacts:** This task scans through relevant `functions` and `connectors` submodules.
    -   For `functions` subprojects (specifically translators), it exclusively looks for and collects their **lean NAR files** (e.g., `user-profile-translator.nar`). If a NAR is not found for a translator, a warning is logged, and no JAR fallback occurs.
    -   For custom `connectors` built from source (e.g., `connectors/grpc`), it now also looks for and collects their **lean NAR files** (e.g., `grpc.nar`).
    -   For other modules (like `functions:splitter` or pre-built connectors defined only by YAML), it may collect lean JARs or other NAR types as configured/available.
-   Test utility modules (e.g., those ending in `-integration`) are excluded.

### Output Directories:
The collected artifacts (including NARs for translators and connectors, and JARs/NARs for other components) are copied into the following directories:
-   `deployment/compose/build/`: Primarily for Docker Compose deployments.
-   `deployment/mesh/`: For deployments using Pulsar Function Mesh.
-   `deployment/worker/`: For traditional Pulsar Function/Connector worker deployments.

This task ensures that you have a consolidated set of deployable units ready for your chosen deployment method. It does *not* build container images directly; container image building (e.g., using Jib) is handled within each individual module's build process (e.g., `./gradlew :functions:translators:user-profile-translator:jibBuildDocker`).

**Note on `bundleForDeploy` and Task Execution:**
The `bundleForDeploy` task is configured to depend on the `build` task (i.e., `dependsOn("build")`). This ensures that Gradle attempts to build all necessary subprojects before bundling artifacts. However, due to Gradle's up-to-date checks, if no source files or build script inputs have changed since the last build, Gradle may mark many tasks as `UP-TO-DATE` and skip their execution for efficiency. If you need to ensure a complete, fresh rebuild of all artifacts before bundling, run `./gradlew clean build` followed by `./gradlew bundleForDeploy`, or combine them like `./gradlew clean bundleForDeploy` (as `bundleForDeploy` triggers `build`).

**Note on Docker Compose and Function NARs:** When using Docker Compose with the generated `bootstrap.sh` script (found in `deployment/compose/`), translator function NARs are deployed using the `--archive` flag. The `bootstrap.sh` script expects these NARs to be available inside the Pulsar container under the `/pulsar/build/` directory. Therefore, you'll need to ensure your `docker-compose.yml` correctly mounts the NAR files from your host's `deployment/compose/build/` directory to `/pulsar/build/` in the Pulsar container (e.g., host `deployment/compose/build/shipment-status-translator.nar` to container `/pulsar/build/shipment-status-translator.nar`).

## Troubleshooting
- **Toolchain issues:** If you see errors related to "No matching toolchains found", ensure you have a JDK 17 installed and discoverable by Gradle, or configure Gradle to auto-download JDKs.
- **Test failures:** Refer to the HTML test reports generated in `<subproject>/build/reports/tests/` for details on any failing tests.
```
