import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.SourceSet
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.api.execution.TaskExecutionGraph
import org.gradle.kotlin.dsl.closureOf
import org.yaml.snakeyaml.Yaml
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File // Ensure this import is present
import java.io.ByteArrayOutputStream 

plugins {
    id("java-library") // Apply java-library to the root project
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.jib) // Jib plugin is now available for subprojects to apply
    alias(libs.plugins.dokka)
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.ben.manes.versions)
    id("jacoco")
}

buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("org.yaml:snakeyaml:1.33") }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17)) // Align to Java 17
        // Add a toolchain resolver
        vendor.set(JvmVendorSpec.AZUL) // Example: Zulu from Azul. This relies on a resolver being configured, e.g. via settings.
    }
}

// Removed the problematic plugins.withType block for JavaToolchainResolverRegistry


jacoco { toolVersion = libs.versions.jacoco.get() }

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "jacoco")
    apply(plugin = "com.diffplug.spotless")

    // Configure Kotlin toolchain after the plugin is applied
    plugins.withId("org.jetbrains.kotlin.jvm") {
        project.extensions.getByType<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>().jvmToolchain(17)
    }

    group = "com.acme.pulsar"
    version = "0.1.0-SNAPSHOT"

    plugins.withId("java") {
        val sourceSets = extensions.getByType<SourceSetContainer>()
        val integrationTest by sourceSets.creating {
            compileClasspath += sourceSets["main"].output
            runtimeClasspath += sourceSets["main"].output
        }
        configurations.maybeCreate("integrationTestImplementation").extendsFrom(configurations["testImplementation"])
        configurations.maybeCreate("integrationTestRuntimeOnly").extendsFrom(configurations["testRuntimeOnly"])

        tasks.register<Test>("integrationTest") {
            description = "Runs integration tests for \${project.name}."
            group = "verification"
            testClassesDirs = integrationTest.output.classesDirs
            classpath = integrationTest.runtimeClasspath
            shouldRunAfter(tasks.named("test"))
            useJUnitPlatform()
        }
    }

    tasks.withType<Test>().configureEach { useJUnitPlatform() }

    configure<com.diffplug.gradle.spotless.SpotlessExtension> { kotlin { ktlint(libs.versions.ktlintCli.get()) } }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } // Explicitly set Kotlin JVM target to 17
    }
}

/**
 * Prepares the project for various deployment scenarios by bundling necessary artifacts.
 *
 * This task serves as a central point for gathering outputs from different parts of the project
 * and organizing them into structures suitable for Docker Compose, Kubernetes (via Helm charts
 * for mesh and worker deployments), and potentially other deployment methods.
 *
 * ### Main Purpose:
 * To build the entire project and collect all necessary artifacts into deployment-ready locations.
 * It ensures that all required components are compiled and their outputs are staged appropriately.
 *
 * ### Dependencies:
 * This task depends on the successful completion of:
 * - `build`: Ensures all subprojects are compiled, tested (if applicable), and their primary artifacts (JARs) are built.
 * - `generateManifests`: Ensures that Helm values, Kubernetes manifests, and Docker Compose bootstrap scripts
 *   are generated based on the current project configuration (e.g., `deployment/pipeline.yaml`).
 *
 * ### Artifact Handling:
 * The task identifies and processes JAR artifacts from specific subprojects:
 * - **Source Subprojects:** It scans all subprojects under `:functions:*` and `:connectors:*`.
 * - **Artifact Identification:** For each of these subprojects, it looks for the main JAR file in their
 *   `build/libs/` directory. It specifically excludes `-plain.jar`, `-sources.jar`, and `-javadoc.jar` files,
 *   aiming for the primary executable or library JAR.
 *   *Note on NARs:* For the purposes of this task and general deployment, the primary JAR output of these
 *   connector and function subprojects is considered the equivalent of a NAR (NiFi Archive) or Pulsar NAR,
 *   containing all necessary code and dependencies for that component.
 * - **Destination Directories:** The identified JAR artifacts are copied to the following locations,
 *   making them available for different deployment mechanisms:
 *     - `deployment/compose/build/`: Primarily for Docker Compose deployments, where these JARs might be
 *       mounted into Pulsar containers.
 *     - `deployment/mesh/`: For deployments using the function mesh, where JARs are typically referenced
 *       by the mesh controller.
 *     - `deployment/worker/`: For traditional Pulsar function/connector worker deployments, where JARs are
 *       placed in the worker's classpath.
 *
 * ### Jib Integration:
 * - **Availability:** The Google Jib plugin (`com.google.cloud.tools.jib`) is made available at the root project level,
 *   meaning any subproject can apply and configure it to build container images.
 * - **Execution Scope:** `bundleForDeploy` itself does *not* execute any Jib tasks (e.g., `jib`, `jibDockerBuild`).
 *   If container images are needed for functions or connectors, Jib tasks must be invoked explicitly
 *   on a per-subproject basis (e.g., `./gradlew :functions:my-function:jibDockerBuild`).
 * - **Image Pushing:** This task does not handle pushing of container images to a registry. Pushing is a
 *   separate concern typically managed by Jib's configuration within each subproject or by CI/CD pipelines.
 *
 * ### Output:
 * The task provides logging output for its actions, including:
 * - Creation of destination directories if they don't exist.
 * - Paths of identified JAR artifacts from each relevant subproject.
 * - Confirmation messages for each JAR file copied to the respective destination directories.
 * - Error messages if any copy operation fails.
 *
 * @see generateManifests Task that generates deployment configurations.
 * @see <a href="https://github.com/GoogleContainerTools/jib/tree/master/jib-gradle-plugin">Jib Gradle Plugin</a>
 */
tasks.register("bundleForDeploy") {
    group = "deployment"
    description = "Bundles artifacts for deployment."
    dependsOn("build", "generateManifests")

    doLast {
        // Jib integration: The Jib plugin is now available. 
        // To build an image for a subproject, apply the Jib plugin in its own build.gradle.kts and configure it.
        // Image pushing is currently not enabled by default in this task.
        // This task currently focuses on copying JARs. Jib tasks (jib, jibDockerBuild) should be run per-project.

        val composeBuildDir = project.file("deployment/compose/build")
        val meshDir = project.file("deployment/mesh")
        val workerDir = project.file("deployment/worker")

        listOf(composeBuildDir, meshDir, workerDir).forEach { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
                println("Created directory: ${dir.absolutePath}")
            }
        }

        val collectedArtifactFiles = mutableListOf<File>()
        subprojects.forEach { subproject ->
            // Only process subprojects that are under :functions or :connectors AND have a build script,
            // AND whose names do not end with "-integration"
            if ((subproject.path.startsWith(":functions") || subproject.path.startsWith(":connectors")) &&
                subproject.file("build.gradle.kts").exists() &&
                !subproject.name.endsWith("-integration")) {
                
                val libsDir = subproject.buildDir.resolve("libs")
                var foundArtifact: File? = null

                if (libsDir.exists() && libsDir.isDirectory) {
                    // Prioritize .nar for :functions subprojects
                    if (subproject.path.startsWith(":functions")) {
                        val narFiles = libsDir.listFiles { file ->
                            file.isFile && file.name.endsWith(".nar")
                        }?.toList() ?: emptyList()

                        if (narFiles.isNotEmpty()) {
                            foundArtifact = narFiles.first()
                            println("Found NAR artifact for ${subproject.path}: ${foundArtifact.absolutePath}")
                        } else {
                            // NAR not found for a function project, log a warning and do not fall back to JAR
                            project.logger.warn("WARNING: No NAR artifact found for function subproject ${subproject.path} in ${libsDir.absolutePath}. Skipping artifact collection for this function.")
                            // foundArtifact remains null, so it won't be added to collectedArtifactFiles
                        }
                    } else { // For non-function projects (e.g., connectors)
                        // Fallback to .jar (existing logic for connectors)
                        val jarFiles = libsDir.listFiles { file ->
                            file.isFile &&
                            file.name.endsWith(".jar") &&
                            !file.name.endsWith("-plain.jar") &&
                            !file.name.endsWith("-sources.jar") &&
                            !file.name.endsWith("-javadoc.jar")
                        }?.toList() ?: emptyList()

                        if (jarFiles.isNotEmpty()) {
                            foundArtifact = jarFiles.first()
                            println("Found JAR artifact for ${subproject.path}: ${foundArtifact.absolutePath}")
                        }
                    }

                    if (foundArtifact != null) {
                        collectedArtifactFiles.add(foundArtifact)
                    } else {
                        println("No suitable artifact (.nar or .jar) found for subproject ${subproject.path} in ${libsDir.absolutePath}")
                    }
                } else {
                    println("Libs directory not found for subproject ${subproject.path}: ${libsDir.absolutePath}")
                }
            }
        }

        if (collectedArtifactFiles.isNotEmpty()) {
            println("\nProcessing artifacts for deployment packaging:")
            collectedArtifactFiles.forEach { sourceArtifactFile ->
                // Copy to deployment/compose/build/
                val targetComposeArtifactFile = composeBuildDir.resolve(sourceArtifactFile.name)
                try {
                    sourceArtifactFile.copyTo(targetComposeArtifactFile, overwrite = true)
                    println("Copied ${sourceArtifactFile.name} to ${composeBuildDir.absolutePath}")
                } catch (e: Exception) {
                    println("ERROR: Could not copy ${sourceArtifactFile.name} to ${composeBuildDir.absolutePath}: ${e.message}")
                }

                // Copy to deployment/mesh/
                val targetMeshArtifactFile = meshDir.resolve(sourceArtifactFile.name)
                try {
                    sourceArtifactFile.copyTo(targetMeshArtifactFile, overwrite = true)
                    println("Copied ${sourceArtifactFile.name} to ${meshDir.absolutePath}")
                } catch (e: Exception) {
                    println("ERROR: Could not copy ${sourceArtifactFile.name} to ${meshDir.absolutePath}: ${e.message}")
                }

                // Copy to deployment/worker/
                val targetWorkerArtifactFile = workerDir.resolve(sourceArtifactFile.name)
                try {
                    sourceArtifactFile.copyTo(targetWorkerArtifactFile, overwrite = true)
                    println("Copied ${sourceArtifactFile.name} to ${workerDir.absolutePath}")
                } catch (e: Exception) {
                    println("ERROR: Could not copy ${sourceArtifactFile.name} to ${workerDir.absolutePath}: ${e.message}")
                }
            }
            println("\nAll identified artifacts processed for deployment packaging.")
        } else {
            println("\nNo artifacts were found to package for deployment.")
        }
    }
}

// Convenience tasks

tasks.register("ciFast") {
    group = "verification"
    description = "Runs Spotless checks and builds (including unit tests)."
    dependsOn("build")
}

tasks.register("ciFull") {
    group = "verification"
    description = "Runs a full CI cycle: build, integration tests, and coverage report."
    dependsOn("build")
    gradle.taskGraph.whenReady(closureOf<TaskExecutionGraph> {
        if (hasTask(this@register.path)) {
            subprojects.flatMap { it.tasks.withType<Test>().filter { t -> t.name == "integrationTest" } }
                .forEach { dependsOn(it) }
        }
    })
    finalizedBy("coverageReport")
}

tasks.register<JacocoReport>("coverageReport") {
    group = "reporting"
    description = "Generates a combined JaCoCo coverage report for all subprojects."

    val allTests = subprojects.flatMap { it.tasks.withType<Test>().matching { t -> t.name in setOf("test","integrationTest") } }
    dependsOn(allTests)
    executionData.from(files(allTests.mapNotNull { it.extensions.findByType(JacocoTaskExtension::class.java)?.destinationFile?.takeIf(File::exists) }))
    val mains = subprojects.mapNotNull { it.extensions.findByType(SourceSetContainer::class.java)?.findByName(SourceSet.MAIN_SOURCE_SET_NAME) }
    sourceDirectories.from(files(mains.flatMap { it.allSource.srcDirs }.filter(File::exists)))
    classDirectories.from(files(mains.flatMap { it.output.classesDirs }.filter(File::exists)))

    reports { xml.required.set(true); html.required.set(true); csv.required.set(false) }

    doLast {
        if (executionData.files.isEmpty()) logger.warn("No JaCoCo data files found.")
        if (sourceDirectories.files.isEmpty()) logger.warn("No source directories found.")
        if (classDirectories.files.isEmpty()) logger.warn("No class directories found.")
    }
}

// -------- START GENERATE MANIFESTS TASK --------

// --- Data Classes ---
data class PipelineGlobalConfig(
    val tenant: String,
    val namespace: String,
    val functions: Map<String, Map<String, Any>> // Raw function data
)

// Using Any? for flexibility, though more specific types are better if known
data class ConnectorInfo(
    val name: String,
    val type: String, // "source" or "sink"
    val image: String,
    val topic: String?,
    val config: Map<String, Any>, // Specific connector config
    val isCustom: Boolean,
    val archive: String?,
    val connectorType: String? // For built-in types
)

data class AdaptedMeshFunction(
    val input: String?, // Changed from inputs list to single input
    val outputs: Any?,   // Changed from output to outputs
    // Include other original function properties as needed
    val originalProperties: Map<String, Any>
)

data class AdaptedMeshConnector(
    val source: Boolean,
    val name: String,
    val image: String?,
    val output: String?, // Topic
    val configRef: String
)

// --- Helper Functions ---

fun loadPipelineGlobalConfig(project: org.gradle.api.Project, yaml: Yaml): PipelineGlobalConfig {
    val pipelineFile = project.file("deployment/pipeline.yaml")
    if (!pipelineFile.exists()) {
        project.logger.warn("deployment/pipeline.yaml not found. Using defaults.")
        return PipelineGlobalConfig("default-tenant", "default-namespace", emptyMap())
    }
    @Suppress("UNCHECKED_CAST")
    val rootCfg = yaml.load<Map<String, Any>>(pipelineFile.readText()) ?: emptyMap()
    val tenant = rootCfg["tenant"] as? String ?: "default-tenant"
    val namespace = rootCfg["namespace"] as? String ?: "default-namespace"

    @Suppress("UNCHECKED_CAST")
    val functionsMap = (rootCfg["functions"] as? Map<String, Any>)
        ?.mapValues { it.value as? Map<String, Any> ?: emptyMap() } // Ensure inner maps are Map<String, Any>
        ?: emptyMap()

    return PipelineGlobalConfig(tenant, namespace, functionsMap)
}

fun loadConnectorInfo(project: org.gradle.api.Project, yaml: Yaml): List<ConnectorInfo> {
    val connectorEntries = mutableListOf<ConnectorInfo>()
    val connectorsDir = project.file("connectors")

    if (!connectorsDir.isDirectory) {
        project.logger.warn("Connectors directory 'connectors/' not found or is not a directory.")
        return emptyList()
    }

    connectorsDir.listFiles()?.forEach { dir ->
        if (!dir.isDirectory) return@forEach
        val ymlFile = dir.resolve("connector.yaml")
        if (!ymlFile.exists()) {
            project.logger.warn("connector.yaml not found in ${dir.path}")
            return@forEach
        }
        try {
            @Suppress("UNCHECKED_CAST")
            val cfg = yaml.load<Map<String, Any>>(ymlFile.readText()) ?: emptyMap()

            @Suppress("UNCHECKED_CAST")
            val specificConfig = (cfg["configFile"] as? String)
                ?.let { dir.resolve(it) }
                ?.takeIf(File::exists)
                ?.let { yaml.load<Map<String, Any>>(it.readText()) }
                ?: emptyMap()

            connectorEntries += ConnectorInfo(
                name = dir.name,
                type = cfg["type"]?.toString()?.lowercase() ?: "unknown",
                image = cfg["image"]?.toString().orEmpty(),
                topic = cfg["topic"]?.toString(),
                config = specificConfig,
                isCustom = dir.resolve("build.gradle.kts").exists(),
                archive = cfg["archive"]?.toString(),
                connectorType = cfg["connectorType"]?.toString()
            )
        } catch (e: Exception) {
            project.logger.error("Error parsing ${ymlFile.absolutePath}: ${e.message}")
        }
    }
    return connectorEntries
}

fun adaptFunctionsForMesh(
    rawFunctions: Map<String, Map<String, Any>>
): Map<String, AdaptedMeshFunction> {
    return rawFunctions.mapValues { (_, funcData) ->
        val newFuncProps = funcData.toMutableMap()
        var adaptedInput: String? = null
        val inputsValue = funcData["inputs"]
        if (inputsValue != null) {
            adaptedInput = if (inputsValue is List<*> && inputsValue.isNotEmpty()) {
                inputsValue.first()?.toString()
            } else if (inputsValue is String) {
                inputsValue
            } else {
                null
            }
            newFuncProps.remove("inputs")
        } else if (funcData.containsKey("input")) {
            adaptedInput = funcData["input"]?.toString()
        }

        var adaptedOutputs: Any? = null
        if (funcData.containsKey("output")) {
            adaptedOutputs = funcData["output"]
            newFuncProps.remove("output")
        }
        AdaptedMeshFunction(
            input = adaptedInput,
            outputs = adaptedOutputs,
            originalProperties = newFuncProps // Store remaining properties
        )
    }
}


fun adaptConnectorsForMesh(connectors: List<ConnectorInfo>): Map<String, AdaptedMeshConnector> {
    return connectors.associateBy { it.name }.mapValues { (_, conn) ->
        AdaptedMeshConnector(
            source = conn.type == "source",
            name = conn.name,
            image = conn.image.takeIf { it.isNotBlank() },
            output = conn.topic, // For sources, this is effectively the output topic
            configRef = "${conn.name}-connector-config"
        )
    }
}

fun generateHelmManifests(
    project: org.gradle.api.Project,
    yaml: Yaml,
    releaseName: String,
    chartDir: File,
    outputDir: File,
    values: Map<String, Any?>,
    description: String // e.g., "Worker" or "Mesh"
) {
    outputDir.mkdirs()
    val valuesFile = project.buildDir.resolve("tmp/${releaseName}_values.yaml").apply { parentFile.mkdirs() }
    valuesFile.writeText(yaml.dump(values))
    project.logger.lifecycle("$description Helm values written to ${valuesFile.absolutePath}")

    val manifestFile = outputDir.resolve("generated-manifests.yaml")
    val execResult = project.exec {
        executable = "helm"
        args = listOf("template", releaseName, chartDir.absolutePath, "--values", valuesFile.absolutePath)
        standardOutput = manifestFile.outputStream()
        errorOutput = System.err // Capture errors
        isIgnoreExitValue = true // Handle non-zero exit codes manually
    }

    if (execResult.exitValue == 0) {
        project.logger.lifecycle("$description manifests written to ${manifestFile.absolutePath}")
    } else {
        project.logger.error(
            "$description Helm template command failed (exit code ${execResult.exitValue}). " +
                    "Manifest file might be incomplete or contain error messages: ${manifestFile.absolutePath}. " +
                    "Check console for Helm errors."
        )
        // Optionally, print the content of manifestFile if it contains error output from Helm
        if (manifestFile.exists() && manifestFile.length() > 0) {
            project.logger.error("Contents of ${manifestFile.name} (may contain Helm error):\n${manifestFile.readText().take(1000)}")
        }
    }
}

fun generateBootstrapScript(
    project: org.gradle.api.Project,
    yaml: Yaml,
    tenant: String,
    namespace: String,
    rawFunctions: Map<String, Map<String, Any>>, // Use raw functions for bootstrap
    connectors: List<ConnectorInfo>,
    composeDir: File,
    connectorConfigsOutDir: File,
    inContainerConnectorConfigsPath: String
) {
    val bsOut = composeDir.resolve("bootstrap.sh")
    val script = StringBuilder().apply {
        appendLine("#!/usr/bin/env bash")
        appendLine("set -e")
        appendLine()
        appendLine("ADMIN_CMD_LOCAL=\"pulsar-admin\"")
        appendLine("PULSAR_CONTAINER_NAME=\"compose-pulsar-1\"")
        appendLine("ADMIN_CMD_DOCKER_EXEC=\"docker exec \${PULSAR_CONTAINER_NAME} bin/pulsar-admin\"")
        appendLine()
        appendLine("TENANT=\"${tenant}\"")
        appendLine("NAMESPACE=\"${namespace}\"")
        appendLine()
        appendLine("echo \"Waiting for Pulsar to be ready (using '\${ADMIN_CMD_LOCAL}')...\"")
        appendLine("until \${ADMIN_CMD_LOCAL} tenants get \${TENANT} > /dev/null 2>&1; do")
        appendLine("  echo -n \".\"")
        appendLine("  sleep 5")
        appendLine("done")
        appendLine("echo \" Pulsar is ready.\"")
        appendLine()
        appendLine("echo \"Creating tenant '\${TENANT}' if it doesn't exist (using '\${ADMIN_CMD_LOCAL}')...\"")
        appendLine("\${ADMIN_CMD_LOCAL} tenants create \${TENANT} --allowed-clusters standalone || echo \"Tenant '\${TENANT}' already exists or error creating.\"")
        appendLine()
        appendLine("echo \"Creating namespace '\${TENANT}/\${NAMESPACE}' if it doesn't exist (using '\${ADMIN_CMD_LOCAL}')...\"")
        appendLine("\${ADMIN_CMD_LOCAL} namespaces create \${TENANT}/\${NAMESPACE} --clusters standalone || echo \"Namespace '\${TENANT}/\${NAMESPACE}' already exists or error creating.\"")
        appendLine()

        if (connectors.isNotEmpty()) {
            appendLine("# --- Deploy Connectors (using '\${ADMIN_CMD_DOCKER_EXEC}') ---")
        }
        connectors.forEach { conn ->
            val connectorAdminSubCommand = when (conn.type) {
                "source" -> "source"
                "sink" -> "sink"
                else -> {
                    project.logger.warn("Unknown connector category '${conn.type}' for connector '${conn.name}'. Skipping bootstrap deployment.")
                    return@forEach
                }
            }

            val configFileName = "${conn.name}-config.yaml"
            val localConfigFile = connectorConfigsOutDir.resolve(configFileName)
            if (conn.config.isNotEmpty()) {
                localConfigFile.writeText(yaml.dump(conn.config))
                project.logger.lifecycle("Bootstrap: Connector config for '${conn.name}' written to ${localConfigFile.absolutePath}")
            }

            appendLine("echo \"Deploying ${conn.type} connector '${conn.name}'...\"")
            val cmd = mutableListOf("\${ADMIN_CMD_DOCKER_EXEC}", connectorAdminSubCommand, "create")
            cmd.add("--tenant \${TENANT}")
            cmd.add("--namespace \${NAMESPACE}")
            cmd.add("--name \"${conn.name}\"")

            if (conn.isCustom) {
                val narFile = conn.archive ?: conn.image.takeIf { it.endsWith(".nar") && it.isNotBlank() } ?: "${conn.name}.nar"
                cmd.add("--archive \"/pulsar/connectors/${narFile}\"")
            } else {
                val typeForBuiltIn = conn.connectorType ?: conn.name
                if (conn.type == "source") cmd.add("--source-type \"${typeForBuiltIn}\"")
                else if (conn.type == "sink") cmd.add("--sink-type \"${typeForBuiltIn}\"")
            }

            if (!conn.topic.isNullOrBlank()) {
                if (conn.type == "source") cmd.add("--destination-topic-name \"${conn.topic}\"")
                if (conn.type == "sink") cmd.add("--inputs \"${conn.topic}\"")
            }

            if (conn.config.isNotEmpty()) {
                val inContainerConfigFilePath = "\"${inContainerConnectorConfigsPath.removeSuffix("/")}/${configFileName}\""
                cmd.add("--${conn.type}-config-file ${inContainerConfigFilePath}")
            }

            appendLine(cmd.joinToString(separator = " \\\n  ") + " || echo \"Failed to create connector '${conn.name}', it might already exist.\"")
            appendLine()
        }
        if (connectors.isNotEmpty()) {
            appendLine("echo \"NOTE: Ensure connector config files from '${connectorConfigsOutDir.name}/' (relative to compose file) are mounted to '${inContainerConnectorConfigsPath}' in \${PULSAR_CONTAINER_NAME}.\"")
            appendLine("echo \"And custom connector NARs are mounted to '/pulsar/connectors/' in \${PULSAR_CONTAINER_NAME}.\"")
            appendLine()
        }

        if (rawFunctions.isNotEmpty()) {
            appendLine("# --- Deploy Functions (using '\${ADMIN_CMD_DOCKER_EXEC}') ---")
        }
        rawFunctions.forEach { (name, func) ->
            val className = func["className"] as? String
            val output = func["output"] as? String // Note: Mesh adaptation uses 'outputs', bootstrap uses 'output'
            val parallelism = func["parallelism"] as? Int
            // Expect NAR file for functions
            val narFileName = func["nar"] as? String ?: "${name}.nar"

            val inputsString = when (val rawInputs = func["inputs"] ?: func["input"]) {
                is List<*> -> rawInputs.mapNotNull { it?.toString() }.joinToString(",")
                is String -> rawInputs
                else -> null
            }

            @Suppress("UNCHECKED_CAST")
            val userConfig = func["userConfig"] as? Map<String, Any> ?: emptyMap()
            val userConfigJson = if (userConfig.isNotEmpty()) {
                userConfig.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { entry ->
                    val key = "\\\"${entry.key}\\\""
                    val value = when (val v = entry.value) {
                        is String -> "\\\"${v.replace("\"", "\\\"").replace("'", "\\'")}\\\""
                        is Number, is Boolean -> v.toString()
                        null -> "null"
                        else -> "\\\"${v.toString().replace("\"", "\\\"").replace("'", "\\'")}\\\""
                    }
                    "$key:$value"
                }
            } else null

            appendLine("echo \"Deploying function '${name}'...\"")
            // Start command construction for functions create
            val commandStart = "\${ADMIN_CMD_DOCKER_EXEC} functions create"
            val cmdOptions = mutableListOf<String>()
            cmdOptions.add("--tenant \${TENANT}")
            cmdOptions.add("--namespace \${NAMESPACE}")
            cmdOptions.add("--name \"${name}\"")
            className?.let { cmdOptions.add("--classname \"$it\"") }
            // Use --archive and the new path for NAR files
            cmdOptions.add("--archive \"/pulsar/build/${narFileName}\"")
            inputsString?.takeIf { it.isNotBlank() }?.let { cmdOptions.add("--inputs \"$it\"") }
            output?.takeIf { it.isNotBlank() }?.let { cmdOptions.add("--output \"$it\"") }
            parallelism?.let { cmdOptions.add("--parallelism $it") }
            userConfigJson?.let { cmdOptions.add("--user-config '$it'") }
            cmdOptions.add("--auto-ack true")

            // Construct the full command string with proper newlines
            appendLine(commandStart + " \\")
            appendLine(cmdOptions.joinToString(separator = " \\\n  ") + " || echo \"Failed to create function '${name}', it might already exist.\"")
            appendLine()
        }
        if (rawFunctions.isNotEmpty()) {
            // Update comment to refer to NARs and the /pulsar/build path
            appendLine("echo \"NOTE: Ensure function NARs are mounted to '/pulsar/build/' in your Pulsar container (\${PULSAR_CONTAINER_NAME}).\"")
            appendLine()
        }

        appendLine("echo \"------------------------------------------\"")
        appendLine("echo \"Pulsar pipeline bootstrap complete for Compose.\"")
        appendLine("echo \"Tenant: \${TENANT}, Namespace: \${NAMESPACE}\"")
        appendLine("echo \"Review notes above for required Docker volume mounts into \${PULSAR_CONTAINER_NAME}.\"")
        appendLine("echo \"------------------------------------------\"")

    }.toString()

    bsOut.writeText(script)
    bsOut.setExecutable(true)
    project.logger.lifecycle("Compose bootstrap script written to ${bsOut.absolutePath}")
    project.logger.lifecycle("Connector configuration files for bootstrap generated in ${connectorConfigsOutDir.absolutePath}")
    project.logger.warn("IMPORTANT: Update your docker-compose.yml to mount:")
    project.logger.warn("  - '${connectorConfigsOutDir.name}' (from deployment/compose/build) to '${inContainerConnectorConfigsPath}' (for connector configs)")
    project.logger.warn("  - Your connector NAR files to '/pulsar/connectors/' (for custom connectors)")
    // Update warning message to refer to NARs and the /pulsar/build path
    project.logger.warn("  - Your function NAR files to '/pulsar/build/' (for functions)")
}


// --- Main Task ---
tasks.register("generateManifests") {
    group = "generation"
    description = "Gathers pipeline data, prepares Helm values, generates manifests, and composes bootstrap script."

    doLast {
        val yaml = Yaml() // Create once

        // 1. Load Configurations
        val pipelineGlobalConfig = loadPipelineGlobalConfig(project, yaml)
        val connectorInfoList = loadConnectorInfo(project, yaml)
        logger.lifecycle("Found ${pipelineGlobalConfig.functions.size} functions and ${connectorInfoList.size} connectors.")

        // 2. Adapt Data for Mesh
        val adaptedFunctionsForMesh = adaptFunctionsForMesh(pipelineGlobalConfig.functions)
        val adaptedConnectorsForMesh = adaptConnectorsForMesh(connectorInfoList)

        // 3. Worker Helm Generation
        val workerChartDir = project.file("deployment/worker")
        val workerOutputDir = project.buildDir.resolve("deploy/worker")
        val workerHelmValues = mapOf(
            "tenant" to pipelineGlobalConfig.tenant,
            "namespace" to pipelineGlobalConfig.namespace,
            "functions" to pipelineGlobalConfig.functions, // Worker uses raw functions
            "connectors" to connectorInfoList.associateBy { it.name } // Worker uses original connector structure
        )
        generateHelmManifests(
            project, yaml,
            "worker-release", workerChartDir, workerOutputDir,
            workerHelmValues, "Worker"
        )

        // 4. Mesh Helm Generation
        val meshChartDir = project.file("deployment/mesh")
        val meshOutputDir = project.buildDir.resolve("deploy/mesh")
        val meshHelmValues = mapOf(
            "tenant" to pipelineGlobalConfig.tenant,
            "namespace" to pipelineGlobalConfig.namespace,
            "mesh" to mapOf(
                "enabled" to true,
                "functions" to adaptedFunctionsForMesh.mapValues { (_, adaptedFunc) ->
                     // Convert AdaptedMeshFunction back to a Map for YAML serialization
                    mapOf(
                        "input" to adaptedFunc.input,
                        "outputs" to adaptedFunc.outputs
                    ) + adaptedFunc.originalProperties // Add back other properties
                },
                "connectors" to adaptedConnectorsForMesh
            )
        )
        generateHelmManifests(
            project, yaml,
            "mesh-release", meshChartDir, meshOutputDir,
            meshHelmValues, "Mesh"
        )

        // 5. Compose Bootstrap Script Generation
        val composeDir = project.file("deployment/compose")
        val connectorConfigsOutDir = composeDir.resolve("build").apply { mkdirs() }
        val inContainerConnectorConfigsPath = "/pulsar/build/"

        generateBootstrapScript(
            project, yaml,
            pipelineGlobalConfig.tenant, pipelineGlobalConfig.namespace,
            pipelineGlobalConfig.functions, // Bootstrap uses raw functions
            connectorInfoList, // Bootstrap uses original connector info
            composeDir, connectorConfigsOutDir, inContainerConnectorConfigsPath
        )
    }
}


// -------- END GENERATE MANIFESTS TASK --------


tasks.register<Exec>("composeUp") {
    group="sandbox";
    description="Starts local dev via Docker Compose";
    dependsOn("generateManifests");
    workingDir=project.file("deployment/compose");
    commandLine("docker","compose","up","-d")
}

tasks.register<Exec>("composeDown") {
    group="sandbox";
    description="Stops and removes containers";
    workingDir=project.file("deployment/compose");
    commandLine("docker","compose","down","--volumes")
}
tasks.register<Exec>("loadTest") {
    group="sandbox";
    description="Runs load test";
    dependsOn("composeUp");
    val rate=project.providers.gradleProperty("loadTest.rate").orElse("100000");
    commandLine("bash","deployment/compose/scripts/load-test.sh","--rate",rate.get())
}

// Example of how a subproject (e.g., a function or connector) can configure Jib to build a container image:
/*
In the subproject's build.gradle.kts:

plugins {
    alias(libs.plugins.jib)
}

jib {
    from {
        image = "eclipse-temurin:17-jre-jammy" // Or any other base image
    }
    to {
        // Example: your-docker-hub-username/my-function-name:0.1.0-SNAPSHOT
        // It's good practice to use project.name and project.version for consistent tagging.
        image = "your-repo/\${project.name}:\${project.version}"
        // tags = setOf("latest", project.version.toString()) // Optional: additional tags
    }
    container {
        // You might need to configure entrypoint, ports, jvmFlags, etc., depending on the application
        // mainClass = "com.example.Application" // If not automatically detected
        // jvmFlags = listOf("-Xms512m", "-Xmx1024m")
        // ports = listOf("8080")
    }
}

// To build the image and push to the configured remote repository:
// ./gradlew :subproject-path:jib
//
// To build the image to your local Docker daemon:
// ./gradlew :subproject-path:jibDockerBuild
//
// Note: Ensure you have credentials configured for your Docker registry if pushing.
// For local Docker daemon, ensure Docker is running.
*/
