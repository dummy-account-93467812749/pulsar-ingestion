# Building the Project

This project uses [Gradle](https://gradle.org/) as its build system. You do not need to install Gradle manually; the project includes a Gradle Wrapper (`gradlew` for Linux/macOS and `gradlew.bat` for Windows) that will automatically download and use the correct Gradle version.

The current Gradle version used by the wrapper is 8.1.1.

## Common Build Operations

Make sure you are in the root directory of the project before running these commands.

### 1. Clean Build Artifacts

To remove all build outputs from previous builds (e.g., compiled classes, JAR files):

```bash
./gradlew clean
```
On Windows:
```bash
.\gradlew.bat clean
```

### 2. Build the Entire Project

To compile all modules, run tests, and assemble all artifacts (e.g., JAR files):

```bash
./gradlew build
```
On Windows:
```bash
.\gradlew.bat build
```
This command will compile the source code, run any unit tests, and create distributable files (like JARs) in the `build` directory of each subproject.

### 3. Run Tests

To execute all unit tests across all subprojects:

```bash
./gradlew test
```
On Windows:
```bash
.\gradlew.bat test
```
Test reports can typically be found in `[subproject]/build/reports/tests/test/index.html`.

## Working with Specific Subprojects

You can build or test individual subprojects. Subprojects are identified by their path from the root project, prefixed with a colon. For example: `:common`, `:connectors:kinesis-source`.

### Build a Specific Subproject

To build a single subproject (e.g., `:common`):

```bash
./gradlew :common:build
```
On Windows:
```bash
.\gradlew.bat :common:build
```
Replace `:common` with the name of the subproject you want to build (e.g., `:connectors:kinesis-source`, `:functions:splitter`).

### Test a Specific Subproject

To run tests for a single subproject (e.g., `:common`):

```bash
./gradlew :common:test
```
On Windows:
```bash
.\gradlew.bat :common:test
```

## Using the Gradle Wrapper (`gradlew`)

The Gradle Wrapper scripts (`gradlew` and `gradlew.bat`) are included in this repository. When you run a command using the wrapper, it automatically:
1. Checks if the specified Gradle version (defined in `gradle/wrapper/gradle-wrapper.properties`) is downloaded.
2. If not, it downloads the correct Gradle version.
3. Executes the requested Gradle task using that version.

This ensures build consistency across different environments and users, as everyone uses the same Gradle version and configuration. **It is the recommended way to interact with the build system.**

## Optional: Installing Gradle Manually

While the wrapper is recommended, if you prefer to use a system-wide Gradle installation:

1.  **Download Gradle**: You can download Gradle from the official [Gradle releases page](https://gradle.org/releases/). Download version 8.1.1 or newer for compatibility.
2.  **Installation**: Follow the installation instructions provided on the Gradle website for your operating system. This usually involves unzipping the downloaded file and adding Gradle's `bin` directory to your system's PATH environment variable.
3.  **Verify Installation**: Open a new terminal window and run `gradle --version` to ensure it's installed correctly.

Even with a system-wide Gradle installation, you can still use the `gradlew` wrapper within this project to ensure you're using the project-defined Gradle version.

## Troubleshooting

*   **`./gradlew: Permission denied` (Linux/macOS)**: If you get a permission error, ensure the `gradlew` script is executable: `chmod +x gradlew`.
*   **Build failures**: Check the console output for error messages. Often, they will point to issues in specific build files or source code.
