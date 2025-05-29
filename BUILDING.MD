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
- **Lean JARs:** Most function and connector submodules (e.g., individual translators, the gRPC connector) are configured to produce lean JAR files. These JARs contain only the module's compiled code and resources, without bundling common dependencies like Pulsar APIs or test frameworks. This makes them suitable for deployment in environments where Pulsar libraries are already provided.
- **NAR files:** Some older modules (like `functions:splitter`) may still produce NAR (NiFi Archive / Pulsar Archive) files, which are fat JARs containing all dependencies.
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
-   **Collects Lean Artifacts:** This task scans through relevant `functions` and `connectors` submodules (specifically those that are built from source within this project, like individual translators or custom connectors such as gRPC).
-   It picks up the primary **lean JAR** file from each of these modules (e.g., `user-profile-translator-0.1.0-SNAPSHOT.jar`, `grpc-0.1.0-SNAPSHOT.jar`). These JARs contain only the module's compiled code and its direct, non-test dependencies, without bundling common libraries like Pulsar APIs or test frameworks.
-   It also collects any pre-existing NAR files from modules that still produce them (e.g., `functions:splitter`).
-   Test utility modules (e.g., those ending in `-integration`) are excluded.

### Output Directories:
The collected artifacts are copied into the following directories, making them available for different deployment mechanisms:
-   `deployment/compose/build/`: Primarily for Docker Compose deployments.
-   `deployment/mesh/`: For deployments using Pulsar Function Mesh.
-   `deployment/worker/`: For traditional Pulsar Function/Connector worker deployments.

This task ensures that you have a consolidated set of deployable units ready for your chosen deployment method. It does *not* build container images directly; container image building (e.g., using Jib) is handled within each individual module's build process (e.g., `./gradlew :functions:translators:user-profile-translator:jibBuildDocker`).

## Troubleshooting
- **Toolchain issues:** If you see errors related to "No matching toolchains found", ensure you have a JDK 17 installed and discoverable by Gradle, or configure Gradle to auto-download JDKs.
- **Test failures:** Refer to the HTML test reports generated in `<subproject>/build/reports/tests/` for details on any failing tests.
```
