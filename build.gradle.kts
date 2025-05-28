import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.SourceSet
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.api.execution.TaskExecutionGraph
import org.gradle.kotlin.dsl.closureOf
import org.yaml.snakeyaml.Yaml
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File // Ensure this import is present

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.jib) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.ben.manes.versions)
    id("jacoco")
}

buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("org.yaml:snakeyaml:1.33") }
}

jacoco { toolVersion = libs.versions.jacoco.get() }

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "jacoco")
    apply(plugin = "com.diffplug.spotless")

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
        compilerOptions { jvmTarget.set(JvmTarget.JVM_23) }
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
    description = "Gathers pipeline data, prepares Helm values, and generates manifests."

    doLast {
        val yaml = Yaml()
        val pipelineFile = project.file("deployment/pipeline.yaml")

        @Suppress("UNCHECKED_CAST")
        val functionsMap: MutableMap<String, Any> = if (pipelineFile.exists()) {
            val cfg = yaml.load<Map<String, Any>>(pipelineFile.readText())
            (cfg["functions"] as? Map<String, Any>)?.toMutableMap() ?: mutableMapOf()
        } else {
            logger.warn("deployment/pipeline.yaml not found. Using defaults.")
            mutableMapOf()
        }

        @Suppress("UNCHECKED_CAST")
        val rootCfg = if (pipelineFile.exists()) yaml.load<Map<String, Any>>(pipelineFile.readText()) else emptyMap<String, Any>()
        val tenant = rootCfg["tenant"] as? String ?: "default-tenant"
        val namespace = rootCfg["namespace"] as? String ?: "default-namespace"

        val connectorEntries = mutableListOf<Map<String, Any>>()
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
                    ?.let { yaml.load<Map<String, Any>>(it.readText()) }
                    ?: emptyMap<String, Any>()
                connectorEntries += mapOf(
                    "name" to dir.name,
                    "type" to cfg["type"]?.toString().orEmpty(),
                    "image" to cfg["image"]?.toString().orEmpty(),
                    "topic" to cfg["topic"]?.toString().orEmpty(),
                    "config" to specific,
                    "isCustom" to dir.resolve("build.gradle.kts").exists()
                )
            } catch (e: Exception) {
                logger.error("Error parsing ${yml.absolutePath}: ${e.message}")
            }
        } ?: logger.warn("Connectors directory missing.")

        println("Found ${functionsMap.size} functions and ${connectorEntries.size} connectors.")

        @Suppress("UNCHECKED_CAST")
        val adaptedFunctionsForMesh = functionsMap.mapValues { (_, funcValue) ->
            val func = funcValue as Map<String, Any>
            val newFunc = func.toMutableMap()

            val inputsValue = func["inputs"]
            if (inputsValue != null) {
                if (inputsValue is List<*> && inputsValue.isNotEmpty()) {
                    newFunc["input"] = inputsValue.first().toString()
                } else if (inputsValue is String) {
                    newFunc["input"] = inputsValue
                }
                if (newFunc.containsKey("input")) { // Only remove if 'input' was successfully set from 'inputs'
                    newFunc.remove("inputs")
                }
            } else if (func.containsKey("input")) {
                newFunc["input"] = func["input"].toString() // Ensure it's a string
            }

            val outputValue = func["output"]
            if (func.containsKey("output")) { // Check if the key "output" exists
                if (outputValue != null) {
                    newFunc["outputs"] = outputValue // Pass the value if not null
                }
                // If outputValue is null, 'outputs' is not set, Helm's 'if $func.outputs' handles it
                newFunc.remove("output") // Remove the original "output" key
            }
            newFunc
        }

        @Suppress("UNCHECKED_CAST")
        val adaptedConnectorsForMesh = connectorEntries.associateBy { it["name"].toString() }.mapValues { (_, connValue) ->
            val conn = connValue as Map<String, Any> // Cast is okay due to associateBy structure
            mapOf(
                "source" to (conn["type"]?.toString()?.lowercase() == "source"),
                "name" to conn["name"],
                "image" to conn["image"],
                "output" to conn["topic"],
                "configRef" to "${conn["name"]}-connector-config"
            )
        }

        // --- Worker Helm Generation ---
        val workerChartDir = project.file("deployment/worker")
        val workerOutputDir = project.buildDir.resolve("deploy/worker").apply { mkdirs() }
        val workerHelmValues = mapOf(
            "tenant" to tenant,
            "namespace" to namespace,
            "functions" to functionsMap, // Using the original functionsMap
            "connectors" to connectorEntries.associateBy { it["name"].toString() } // Using connectorEntries, adapted as a map by name
        )
        val workerValuesFile = project.buildDir.resolve("tmp/worker_values.yaml").apply { parentFile.mkdirs() }
        workerValuesFile.writeText(yaml.dump(workerHelmValues))
        println("Worker Helm values written to ${workerValuesFile.absolutePath}")

        val workerManifestFile = workerOutputDir.resolve("generated-manifests.yaml")
        val workerOutputBaos = java.io.ByteArrayOutputStream()
        val workerErrorBaos = java.io.ByteArrayOutputStream()
        val workerExecResult = project.exec {
            executable = "helm"
            args = listOf("template", "worker-release", workerChartDir.absolutePath, "--values", workerValuesFile.absolutePath)
            standardOutput = workerOutputBaos
            errorOutput = workerErrorBaos
            isIgnoreExitValue = true // Handle errors manually
        }

        if (workerExecResult.exitValue != 0) {
            logger.error("Worker Helm template command failed. Exit code: ${workerExecResult.exitValue}")
            logger.error("Worker Helm stderr: ${workerErrorBaos.toString().trim()}")
            workerManifestFile.writeText("# Helm generation failed\n# Error: ${workerErrorBaos.toString().trim()}")
        } else {
            workerManifestFile.writeText(workerOutputBaos.toString())
            println("Worker manifests written to ${workerManifestFile.absolutePath}")
        }

        // --- Mesh Helm Generation ---
        val meshChartDir = project.file("deployment/mesh")
        val meshOutputDir = project.buildDir.resolve("deploy/mesh").apply { mkdirs() }
        val meshHelmValues = mapOf(
            "tenant" to tenant,
            "namespace" to namespace,
            "mesh" to mapOf(
                "enabled" to true, // Assuming 'enabled' is a required field for the mesh chart
                "functions" to adaptedFunctionsForMesh,
                "connectors" to adaptedConnectorsForMesh
            )
        )
        val meshValuesFile = project.buildDir.resolve("tmp/mesh_values.yaml").apply { parentFile.mkdirs() }
        meshValuesFile.writeText(yaml.dump(meshHelmValues))
        println("Mesh Helm values written to ${meshValuesFile.absolutePath}")

        val meshManifestFile = meshOutputDir.resolve("generated-manifests.yaml")
        val meshOutputBaos = java.io.ByteArrayOutputStream()
        val meshErrorBaos = java.io.ByteArrayOutputStream()
        val meshExecResult = project.exec {
            executable = "helm"
            args = listOf("template", "mesh-release", meshChartDir.absolutePath, "--values", meshValuesFile.absolutePath)
            standardOutput = meshOutputBaos
            errorOutput = meshErrorBaos
            isIgnoreExitValue = true // Handle errors manually
        }

        if (meshExecResult.exitValue != 0) {
            logger.error("Mesh Helm template command failed. Exit code: ${meshExecResult.exitValue}")
            logger.error("Mesh Helm stderr: ${meshErrorBaos.toString().trim()}")
            meshManifestFile.writeText("# Helm generation failed\n# Error: ${meshErrorBaos.toString().trim()}")
        } else {
            meshManifestFile.writeText(meshOutputBaos.toString())
            println("Mesh manifests written to ${meshManifestFile.absolutePath}")
        }

        // --- Compose Bootstrap Script Generation ---
        val bsOut = project.file("deployment/compose/bootstrap.sh")
        val scriptContent = StringBuilder()

        scriptContent.appendLine("#!/usr/bin/env bash")
        scriptContent.appendLine("set -e") // Exit on error
        scriptContent.appendLine("")
        scriptContent.appendLine("ADMIN_CMD=\"pulsar-admin\"")
        scriptContent.appendLine("TENANT=\"${tenant}\"")
        scriptContent.appendLine("NAMESPACE=\"${namespace}\"")
        scriptContent.appendLine("")
        scriptContent.appendLine("echo \"Waiting for Pulsar to be ready...\"")
        scriptContent.appendLine("until \${ADMIN_CMD} tenants get \${TENANT} > /dev/null 2>&1; do")
        scriptContent.appendLine("  echo -n \".\"")
        scriptContent.appendLine("  sleep 5")
        scriptContent.appendLine("done")
        scriptContent.appendLine("echo \"Pulsar is ready.\"")
        scriptContent.appendLine("")
        scriptContent.appendLine("echo \"Creating tenant '\${TENANT}' if it doesn't exist...\"")
        scriptContent.appendLine("\${ADMIN_CMD} tenants create \${TENANT} --allowed-clusters standalone || echo \"Tenant '\${TENANT}' already exists or error creating.\"")
        scriptContent.appendLine("")
        scriptContent.appendLine("echo \"Creating namespace '\${TENANT}/\${NAMESPACE}' if it doesn't exist...\"")
        scriptContent.appendLine("\${ADMIN_CMD} namespaces create \${TENANT}/\${NAMESPACE} --clusters standalone || echo \"Namespace '\${TENANT}/\${NAMESPACE}' already exists or error creating.\"")
        scriptContent.appendLine("")

        // Connector Deployment
        scriptContent.appendLine("# --- Deploy Connectors ---")
        connectorEntries.forEach { connectorEntry ->
            val name = connectorEntry["name"] as String
            val type = connectorEntry["type"] as? String ?: "unknown" // source or sink
            val image = connectorEntry["image"] as? String
            val topic = connectorEntry["topic"] as? String // Used as output for source, input for sink
            @Suppress("UNCHECKED_CAST")
            val config = connectorEntry["config"] as? Map<String, Any> ?: emptyMap()
            val isCustom = connectorEntry["isCustom"] as? Boolean ?: false

            // Convert config map to JSON string manually for simplicity
            // A proper JSON library would be more robust for complex configs
            val configJson = config.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { entry ->
                "\\\"${entry.key}\\\":${if (entry.value is String) "\\\"${entry.value}\\\"" else entry.value.toString()}"
            }

            val connectorCmd = when (type.lowercase()) {
                "source" -> "sources"
                "sink" -> "sinks"
                else -> {
                    logger.warn("Unknown connector type '$type' for connector '$name'. Skipping.")
                    return@forEach
                }
            }

            scriptContent.appendLine("echo \"Deploying ${type.lowercase()} connector '${name}'...\"")
            val cmd = mutableListOf("\${ADMIN_CMD}", connectorCmd, "create")
            cmd.add("--tenant \${TENANT}")
            cmd.add("--namespace \${NAMESPACE}")
            cmd.add("--name \"${name}\"")

            val connectorTypeFromConfig = connectorEntry["type"] as? String // this 'type' is 'source' or 'sink'
            // val actualConnectorType = image // this 'image' field from connector.yaml is NOT the connector type for built-ins

            if (isCustom) {
                // For custom connectors, 'image' field in connector.yaml might hold the archive name or be part of it.
                // We'll prefer a convention <name>.nar if image is not an explicit archive path.
                // The 'archive' field in connector.yaml would be even better if it existed.
                // For bootstrap.sh, we assume the NAR is named <name>.nar and is in /pulsar/connectors/
                cmd.add("--archive \"/pulsar/connectors/${name}.nar\"")
            } else {
                // For non-custom (built-in) connectors, the connector's 'name' (e.g., "kafka", "kinesis") is its type.
                if (connectorTypeFromConfig?.lowercase() == "source") {
                    cmd.add("--source-type \"${name}\"")
                } else if (connectorTypeFromConfig?.lowercase() == "sink") {
                    cmd.add("--sink-type \"${name}\"")
                } else {
                    logger.warn("Connector '${name}' is not custom and has an unknown type ('${connectorTypeFromConfig}'). Cannot determine --source-type or --sink-type.")
                }
            }

            if (connectorTypeFromConfig?.lowercase() == "source" && topic != null) cmd.add("--topic-name \"${topic}\"") // Source's output topic
            if (connectorTypeFromConfig?.lowercase() == "sink" && topic != null) cmd.add("--inputs \"${topic}\"") // Sink's input topic

            if (config.isNotEmpty()) {
                cmd.add("--${type.lowercase()}-config '${configJson}'")
            }
            // Add other common parameters like parallelism if available in connectorEntry
            // cmd.add("--parallelism X")

            scriptContent.appendLine(cmd.joinToString(separator = " \\\n  ") + " || echo \"Failed to create connector '${name}', it might already exist.\"")
            scriptContent.appendLine("")
        }

        // Function Deployment
        scriptContent.appendLine("# --- Deploy Functions ---")
        functionsMap.forEach { (name, funcValue) ->
            @Suppress("UNCHECKED_CAST")
            val func = funcValue as Map<String, Any>
            val className = func["className"] as? String
            // val image = func["image"] as? String // This is a Docker image, not the JAR name for bootstrap.sh
            val output = func["output"] as? String
            val parallelism = func["parallelism"] as? Int

            // Handle inputs: could be a single string or a list of strings
            val rawInputs = func["inputs"] ?: func["input"]
            val inputsString = when (rawInputs) {
                is List<*> -> (rawInputs as List<String>).joinToString(",")
                is String -> rawInputs
                else -> null
            }

            @Suppress("UNCHECKED_CAST")
            val userConfig = func["userConfig"] as? Map<String, Any> ?: emptyMap()

            // Convert userConfig map to JSON string manually
            val userConfigJson = userConfig.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { entry ->
                "\\\"${entry.key}\\\":${if (entry.value is String) "\\\"${entry.value}\\\"" else entry.value.toString()}"
            }

            scriptContent.appendLine("echo \"Deploying function '${name}'...\"")
            val cmd = mutableListOf("\${ADMIN_CMD}", "functions", "create")
            cmd.add("--tenant \${TENANT}")
            cmd.add("--namespace \${NAMESPACE}")
            cmd.add("--name \"${name}\"")

            if (className != null) cmd.add("--classname \"${className}\"")
            // For bootstrap.sh, JAR is assumed to be <function-name>.jar in /pulsar/functions/
            cmd.add("--jar \"/pulsar/functions/${name}.jar\"")

            if (inputsString != null && inputsString.isNotBlank()) {
                cmd.add("--inputs \"${inputsString}\"")
            }
            if (output != null) cmd.add("--output \"${output}\"")
            if (parallelism != null) cmd.add("--parallelism ${parallelism}")
            if (userConfig.isNotEmpty()) {
                cmd.add("--user-config '${userConfigJson}'")
            }
            cmd.add("--auto-ack true") // Common default

            scriptContent.appendLine(cmd.joinToString(separator = " \\\n  ") + " || echo \"Failed to create function '${name}', it might already exist.\"")
            scriptContent.appendLine("")
        }

        scriptContent.appendLine("echo \"------------------------------------------\"")
        scriptContent.appendLine("echo \"Pulsar pipeline bootstrap complete.\"")
        scriptContent.appendLine("echo \"Tenant: \${TENANT}, Namespace: \${NAMESPACE}\"")
        scriptContent.appendLine("echo \"Deployed functions and connectors.\"")
        scriptContent.appendLine("echo \"------------------------------------------\"")

        bsOut.writeText(scriptContent.toString())
        bsOut.setExecutable(true)
        println("Compose bootstrap script written to ${bsOut.absolutePath}")
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