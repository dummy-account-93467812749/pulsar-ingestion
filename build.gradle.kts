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
    }
}

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
            if (subproject.path.startsWith(":functions") || subproject.path.startsWith(":connectors")) {
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
                        }
                    }

                    // Fallback to .jar if .nar not found or not a :functions project
                    if (foundArtifact == null) {
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

tasks.register("generateManifests") {
    group = "generation"
    description = "Gathers pipeline data, prepares Helm values, generates manifests, and compose bootstrap script."

    doLast {
        val yaml = Yaml()
        val pipelineFile = project.file("deployment/pipeline.yaml")

        @Suppress("UNCHECKED_CAST")
        val functionsMap: MutableMap<String, Any> = if (pipelineFile.exists()) {
            val cfg = yaml.load<Map<String, Any>>(pipelineFile.readText())
            (cfg["functions"] as? Map<String, Any>)?.toMutableMap() ?: mutableMapOf()
        } else {
            logger.warn("deployment/pipeline.yaml not found. Using defaults for functions.")
            mutableMapOf()
        }

        @Suppress("UNCHECKED_CAST")
        val rootCfg = if (pipelineFile.exists()) yaml.load<Map<String, Any>>(pipelineFile.readText()) else emptyMap<String, Any>()
        val tenant = rootCfg["tenant"] as? String ?: "default-tenant"
        val namespace = rootCfg["namespace"] as? String ?: "default-namespace"

        // Fix 1: Allow null values in the map for optional connector properties
        val connectorEntries = mutableListOf<Map<String, Any?>>()
        project.file("connectors").takeIf(File::isDirectory)?.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val yml = dir.resolve("connector.yaml")
            if (!yml.exists()) {
                logger.warn("connector.yaml not found in ${dir.path}")
                return@forEach
            }
            try {
                @Suppress("UNCHECKED_CAST")
                val cfg = yaml.load<Map<String, Any>>(yml.readText())
                @Suppress("UNCHECKED_CAST")
                val specific = (cfg["configFile"] as? String)
                    ?.let { dir.resolve(it) }
                    ?.takeIf(File::exists)
                    ?.let { yaml.load<Map<String, Any>>(it.readText()) } // This is Map<String, Any>
                    ?: emptyMap<String, Any>()

                // This mapOf will correctly infer Map<String, Any?> because some values can be null
                connectorEntries += mapOf(
                    "name" to dir.name, // String (non-null)
                    "type" to cfg["type"]?.toString().orEmpty(), // String (non-null)
                    "image" to cfg["image"]?.toString().orEmpty(), // String (non-null, but could be empty)
                    "topic" to cfg["topic"]?.toString().orEmpty(), // String (non-null, but could be empty)
                    "config" to specific, // Map<String, Any> (non-null map, values are Any)
                    "isCustom" to dir.resolve("build.gradle.kts").exists(), // Boolean (non-null)
                    "archive" to cfg["archive"]?.toString(), // String? (nullable)
                    "connectorType" to cfg["connectorType"]?.toString() // String? (nullable)
                )
            } catch (e: Exception) {
                logger.error("Error parsing ${yml.absolutePath}: ${e.message}")
            }
        } ?: logger.warn("Connectors directory missing.")

        println("Found ${functionsMap.size} functions and ${connectorEntries.size} connectors.")

        val adaptedFunctionsForMesh = functionsMap.mapValues { (_, funcValue) ->
            @Suppress("UNCHECKED_CAST") // funcValue is Any, casting to Map
            val func = funcValue as Map<String, Any>
            val newFunc = func.toMutableMap()
            val inputsValue = func["inputs"]
            if (inputsValue != null) {
                if (inputsValue is List<*> && inputsValue.isNotEmpty()) {
                    newFunc["input"] = inputsValue.first()?.toString() ?: "" // Handle null element
                } else if (inputsValue is String) {
                    newFunc["input"] = inputsValue
                }
                if (newFunc.containsKey("input")) {
                    newFunc.remove("inputs")
                }
            } else if (func.containsKey("input")) {
                newFunc["input"] = func["input"]?.toString() ?: "" // Handle null
            }
            val outputValue = func["output"]
            if (func.containsKey("output")) {
                if (outputValue != null) {
                    newFunc["outputs"] = outputValue
                }
                newFunc.remove("output")
            }
            newFunc // This will be MutableMap<String, Any?> if any values became null
        }

        // connectorEntries is List<Map<String, Any?>>
        // it["name"] is Any?, but dir.name should make it String. Use safe cast or default.
        // connValue will be Map<String, Any?>
        val adaptedConnectorsForMesh = connectorEntries.associateBy { it["name"] as String }.mapValues { (_, connValue) ->
            // Fix 2: connValue is already Map<String, Any?>, no need to cast to Map<String, Any>
            val conn = connValue // conn is Map<String, Any?>

            // This mapOf will correctly create a Map<String, Any?>
            mapOf(
                "source" to (conn["type"]?.toString()?.lowercase() == "source"), // Boolean
                "name" to conn["name"], // Should be String from dir.name
                "image" to conn["image"], // String?
                "output" to conn["topic"], // String?
                "configRef" to "${conn["name"]}-connector-config" // String interpolation handles null by inserting "null"
            )
        }

        // --- Worker Helm Generation ---
        val workerChartDir = project.file("deployment/worker")
        val workerOutputDir = project.buildDir.resolve("deploy/worker").apply { mkdirs() }
        // connectorEntries.associateBy is fine, it will be Map<String, Map<String, Any?>>
        val workerHelmValues = mapOf(
            "tenant" to tenant,
            "namespace" to namespace,
            "functions" to functionsMap, // functionsMap is Map<String, Any>, where Any can be Map<String,Any>
            "connectors" to connectorEntries.associateBy { it["name"] as String }
        )
        val workerValuesFile = project.buildDir.resolve("tmp/worker_values.yaml").apply { parentFile.mkdirs() }
        workerValuesFile.writeText(yaml.dump(workerHelmValues))
        println("Worker Helm values written to ${workerValuesFile.absolutePath}")

        val workerManifestFile = workerOutputDir.resolve("generated-manifests.yaml")
        project.exec {
            executable = "helm"
            args = listOf("template", "worker-release", workerChartDir.absolutePath, "--values", workerValuesFile.absolutePath)
            standardOutput = workerManifestFile.outputStream()
            errorOutput = System.err
            isIgnoreExitValue = true
        }.takeIf { it.exitValue == 0 }?.let {
            println("Worker manifests written to ${workerManifestFile.absolutePath}")
        } ?: logger.error("Worker Helm template command failed. See console for errors. Manifest file might be incomplete or contain error messages.")


        // --- Mesh Helm Generation ---
        val meshChartDir = project.file("deployment/mesh")
        val meshOutputDir = project.buildDir.resolve("deploy/mesh").apply { mkdirs() }
        // adaptedFunctionsForMesh can be Map<String, Map<String, Any?>>
        // adaptedConnectorsForMesh is Map<String, Map<String, Any?>>
        val meshHelmValues = mapOf(
            "tenant" to tenant,
            "namespace" to namespace,
            "mesh" to mapOf(
                "enabled" to true,
                "functions" to adaptedFunctionsForMesh,
                "connectors" to adaptedConnectorsForMesh
            )
        )
        val meshValuesFile = project.buildDir.resolve("tmp/mesh_values.yaml").apply { parentFile.mkdirs() }
        meshValuesFile.writeText(yaml.dump(meshHelmValues))
        println("Mesh Helm values written to ${meshValuesFile.absolutePath}")

        val meshManifestFile = meshOutputDir.resolve("generated-manifests.yaml")
        project.exec {
            executable = "helm"
            args = listOf("template", "mesh-release", meshChartDir.absolutePath, "--values", meshValuesFile.absolutePath)
            standardOutput = meshManifestFile.outputStream()
            errorOutput = System.err
            isIgnoreExitValue = true
        }.takeIf { it.exitValue == 0 }?.let {
            println("Mesh manifests written to ${meshManifestFile.absolutePath}")
        } ?: logger.error("Mesh Helm template command failed. See console for errors. Manifest file might be incomplete or contain error messages.")


        // --- Compose Bootstrap Script Generation ---

        val composeDir = project.file("deployment/compose")
        val connectorConfigsOutDir = composeDir.resolve("build").apply { mkdirs() }
        val inContainerConnectorConfigsPath = "/pulsar/build/" // For connector YAML configs. Ends with a slash.

        val bsOut = composeDir.resolve("bootstrap.sh")
        val scriptContent = StringBuilder()

        scriptContent.appendLine("#!/usr/bin/env bash")
        scriptContent.appendLine("set -e")
        scriptContent.appendLine("")
        scriptContent.appendLine("# Command for admin operations executed directly (tenant, namespace, readiness check)")
        scriptContent.appendLine("ADMIN_CMD_LOCAL=\"pulsar-admin\"")
        scriptContent.appendLine("# Command for admin operations executed via docker exec (connectors, functions)")
        scriptContent.appendLine("PULSAR_CONTAINER_NAME=\"compose-pulsar-1\"") // Define container name for messages and exec
        scriptContent.appendLine("ADMIN_CMD_DOCKER_EXEC=\"docker exec \${PULSAR_CONTAINER_NAME} bin/pulsar-admin\"")
        scriptContent.appendLine("")
        scriptContent.appendLine("TENANT=\"${tenant}\"")
        scriptContent.appendLine("NAMESPACE=\"${namespace}\"")
        scriptContent.appendLine("")
        scriptContent.appendLine("echo \"Waiting for Pulsar to be ready (using '\${ADMIN_CMD_LOCAL}')...\"")
        scriptContent.appendLine("until \${ADMIN_CMD_LOCAL} tenants get \${TENANT} > /dev/null 2>&1; do") // Use local admin
        scriptContent.appendLine("  echo -n \".\"")
        scriptContent.appendLine("  sleep 5")
        scriptContent.appendLine("done")
        scriptContent.appendLine("echo \"Pulsar is ready.\"")
        scriptContent.appendLine("")
        scriptContent.appendLine("echo \"Creating tenant '\${TENANT}' if it doesn't exist (using '\${ADMIN_CMD_LOCAL}')...\"")
        scriptContent.appendLine("\${ADMIN_CMD_LOCAL} tenants create \${TENANT} --allowed-clusters standalone || echo \"Tenant '\${TENANT}' already exists or error creating.\"") // Use local admin
        scriptContent.appendLine("")
        scriptContent.appendLine("echo \"Creating namespace '\${TENANT}/\${NAMESPACE}' if it doesn't exist (using '\${ADMIN_CMD_LOCAL}')...\"")
        scriptContent.appendLine("\${ADMIN_CMD_LOCAL} namespaces create \${TENANT}/\${NAMESPACE} --clusters standalone || echo \"Namespace '\${TENANT}/\${NAMESPACE}' already exists or error creating.\"") // Use local admin
        scriptContent.appendLine("")

        scriptContent.appendLine("# --- Deploy Connectors (using '\${ADMIN_CMD_DOCKER_EXEC}') ---")
        connectorEntries.forEach { connectorEntry -> // connectorEntry is Map<String, Any?>
            val name = connectorEntry["name"] as String
            val connectorCategory = connectorEntry["type"] as? String ?: "unknown"
            @Suppress("UNCHECKED_CAST")
            val config = connectorEntry["config"] as? Map<String, Any> ?: emptyMap()
            val isCustom = connectorEntry["isCustom"] as? Boolean ?: false
            val archiveName = connectorEntry["archive"] as? String
            val connectorTypeForBuiltIn = (connectorEntry["connectorType"] as? String) ?: name
            val topic = connectorEntry["topic"] as? String

            // MODIFICATION 1: Use singular "source" and "sink" for the command part
            val connectorAdminSubCommand = when (connectorCategory.lowercase()) {
                "source" -> "source" // Singular
                "sink" -> "sink"     // Singular
                else -> {
                    logger.warn("Unknown connector category '$connectorCategory' for connector '$name'. Skipping bootstrap deployment.")
                    return@forEach
                }
            }

            val configFileName = "${name}-config.yaml" // Does not start with a slash
            val localConfigFile = connectorConfigsOutDir.resolve(configFileName)
            if (config.isNotEmpty()) {
                localConfigFile.writeText(yaml.dump(config))
                println("Connector config for '${name}' written to ${localConfigFile.absolutePath}")
            } else {
                println("Connector '${name}' has no specific configuration to write to a file.")
            }

            scriptContent.appendLine("echo \"Deploying ${connectorCategory.lowercase()} connector '${name}'...\"")
            // Use ADMIN_CMD_DOCKER_EXEC for deploying connectors and the modified connectorAdminSubCommand
            val cmd = mutableListOf("\${ADMIN_CMD_DOCKER_EXEC}", connectorAdminSubCommand, "create")
            cmd.add("--tenant \${TENANT}")
            cmd.add("--namespace \${NAMESPACE}")
            cmd.add("--name \"${name}\"")

            if (isCustom) {
                val image = connectorEntry["image"] as? String
                val narFile = archiveName ?: image?.takeIf { it.endsWith(".nar") && it.isNotBlank() } ?: "${name}.nar"
                cmd.add("--archive \"/pulsar/connectors/${narFile}\"")
            } else {
                if (connectorCategory.lowercase() == "source") {
                    cmd.add("--source-type \"${connectorTypeForBuiltIn}\"")
                } else if (connectorCategory.lowercase() == "sink") {
                    cmd.add("--sink-type \"${connectorTypeForBuiltIn}\"")
                } else {
                     logger.warn("Connector '${name}' is not custom and has an unknown category ('${connectorCategory}'). Cannot determine --source-type or --sink-type.")
                }
            }

            // MODIFICATION 2: Use --destination-topic-name for sources
            if (connectorCategory.lowercase() == "source" && !topic.isNullOrBlank()) {
                cmd.add("--destination-topic-name \"${topic}\"")
            }
            if (connectorCategory.lowercase() == "sink" && !topic.isNullOrBlank()) {
                cmd.add("--inputs \"${topic}\"")
            }

            if (config.isNotEmpty()) {
                // MODIFICATION 3: Fix potential double slash by ensuring inContainerConnectorConfigsPath ends with /
                // and configFileName does not start with /.
                // Given inContainerConnectorConfigsPath = "/pulsar/build/" (ends with /)
                // and configFileName = "${name}-config.yaml" (does not start with /)
                // Concatenating them directly is correct.
                val inContainerConfigFilePath = "\"${inContainerConnectorConfigsPath}${configFileName}\""
                cmd.add("--${connectorCategory.lowercase()}-config-file ${inContainerConfigFilePath}") // e.g. --source-config-file
            }

            scriptContent.appendLine(cmd.joinToString(separator = " \\\n  ") + " || echo \"Failed to create connector '${name}', it might already exist.\"")
            scriptContent.appendLine("")
        }
        if (connectorEntries.isNotEmpty()) {
             scriptContent.appendLine("echo \"NOTE: Ensure connector config files from 'deployment/compose/build/' are mounted to '${inContainerConnectorConfigsPath}' in your Pulsar container (\${PULSAR_CONTAINER_NAME}).\"")
             scriptContent.appendLine("echo \"And custom connector NARs are mounted to '/pulsar/connectors/' in \${PULSAR_CONTAINER_NAME}.\"")
             scriptContent.appendLine("")
        }

        scriptContent.appendLine("# --- Deploy Functions (using '\${ADMIN_CMD_DOCKER_EXEC}') ---")
        functionsMap.forEach { (name, funcValue) -> // funcValue is Any
            @Suppress("UNCHECKED_CAST")
            val func = funcValue as Map<String, Any> // func is Map<String, Any>
            val className = func["className"] as? String
            val output = func["output"] as? String
            val parallelism = func["parallelism"] as? Int
            val jarFileName = func["jar"] as? String ?: "${name}.jar"

            val rawInputs = func["inputs"] ?: func["input"] // rawInputs is Any?
            val inputsString = when (val currentRawInputs = rawInputs) {
                is List<*> -> currentRawInputs.mapNotNull { it?.toString() }.joinToString(",")
                is String -> currentRawInputs
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
                        null -> "null" // Handle explicit nulls in userConfig if any
                        else -> "\\\"${v.toString().replace("\"", "\\\"").replace("'", "\\'")}\\\""
                    }
                    "$key:$value"
                }
            } else {
                null
            }

            scriptContent.appendLine("echo \"Deploying function '${name}'...\"")
            val cmd = mutableListOf("\${ADMIN_CMD_DOCKER_EXEC}", "functions", "create")
            cmd.add("--tenant \${TENANT}")
            cmd.add("--namespace \${NAMESPACE}")
            cmd.add("--name \"${name}\"")

            if (className != null) cmd.add("--classname \"${className}\"")
            cmd.add("--jar \"/pulsar/functions/${jarFileName}\"")

            if (inputsString != null && inputsString.isNotBlank()) {
                cmd.add("--inputs \"${inputsString}\"")
            }
            if (output != null && output.isNotBlank()) cmd.add("--output \"${output}\"")
            if (parallelism != null) cmd.add("--parallelism ${parallelism}")
            if (userConfigJson != null) {
                cmd.add("--user-config '${userConfigJson}'")
            }
            cmd.add("--auto-ack true")

            scriptContent.appendLine(cmd.joinToString(separator = " \\\n  ") + " || echo \"Failed to create function '${name}', it might already exist.\"")
            scriptContent.appendLine("")
        }
         if (functionsMap.isNotEmpty()) {
             scriptContent.appendLine("echo \"NOTE: Ensure function JARs are mounted to '/pulsar/functions/' in your Pulsar container (\${PULSAR_CONTAINER_NAME}).\"")
             scriptContent.appendLine("")
        }

        scriptContent.appendLine("echo \"------------------------------------------\"")
        scriptContent.appendLine("echo \"Pulsar pipeline bootstrap complete for Compose.\"")
        scriptContent.appendLine("echo \"Tenant: \${TENANT}, Namespace: \${NAMESPACE}\"")
        scriptContent.appendLine("echo \"Review notes above for required Docker volume mounts into \${PULSAR_CONTAINER_NAME}.\"")
        scriptContent.appendLine("echo \"------------------------------------------\"")

        bsOut.writeText(scriptContent.toString())
        bsOut.setExecutable(true)
        println("Compose bootstrap script written to ${bsOut.absolutePath}")
        println("Connector configuration files generated in ${connectorConfigsOutDir.absolutePath}")
        println("IMPORTANT: Update your docker-compose.yml to mount:")
        println("  - '${connectorConfigsOutDir.absolutePath}' to '${inContainerConnectorConfigsPath}' (for connector configs)")
        println("  - Your connector NAR files to '/pulsar/connectors/' (for custom connectors)")
        println("  - Your function JAR files to '/pulsar/functions/' (for functions)")
    }
}

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
