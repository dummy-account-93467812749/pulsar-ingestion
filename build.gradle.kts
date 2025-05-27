// Root build.gradle.kts
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.SourceSet
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.api.execution.TaskExecutionGraph // Import for TaskExecutionGraph
import org.gradle.kotlin.dsl.closureOf            // Import for closureOf
import groovy.yaml.YamlSlurper // Import for YAML parsing
import groovy.yaml.YamlBuilder // Import for YAML building

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.jib) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.ben.manes.versions)
    id("jacoco")
}

// Configure JaCoCo plugin globally
jacoco {
    toolVersion = libs.versions.jacoco.get()
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "jacoco") // JaCoCo plugin applied to each subproject
    apply(plugin = "com.diffplug.spotless")

    group = "com.acme.pulsar"
    version = "0.1.0-SNAPSHOT"

    plugins.withId("java") { // This ensures the configuration runs after 'java' (or 'kotlin.jvm' which applies 'java') is applied
        val sourceSets = extensions.getByType<SourceSetContainer>()
        val integrationTest by sourceSets.creating {
            compileClasspath += sourceSets.getByName("main").output
            runtimeClasspath += sourceSets.getByName("main").output
        }
        val integrationTestImplementation = configurations.maybeCreate("integrationTestImplementation")
        integrationTestImplementation.extendsFrom(configurations.getByName("testImplementation"))
        val integrationTestRuntimeOnly = configurations.maybeCreate("integrationTestRuntimeOnly")
        integrationTestRuntimeOnly.extendsFrom(configurations.getByName("testRuntimeOnly"))

        tasks.register<Test>("integrationTest") {
            description = "Runs integration tests for ${project.name}."
            group = "verification"
            testClassesDirs = integrationTest.output.classesDirs
            classpath = integrationTest.runtimeClasspath
            shouldRunAfter(tasks.named("test"))
            useJUnitPlatform()
            // The JacocoTaskExtension is automatically added by the jacoco plugin.
            // If you need to configure it specifically for this task, you can do so here.
            // For just enabling it, the plugin being applied is enough.
            // The toolVersion is set globally.
            // configure<JacocoTaskExtension> {} // This is often not needed if defaults are fine
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // The JacocoTaskExtension is automatically added by the jacoco plugin.
        // The toolVersion is set globally.
        // configure<JacocoTaskExtension> {} // This is often not needed if defaults are fine
    }

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            ktlint(libs.versions.ktlintCli.get())
        }
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_23)
        }
    }
}

/* ---------- convenience tasks ---------- */
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
        if (this.hasTask(this@register.path)) {
            val integrationTestTasks = subprojects.flatMap { subproject ->
                subproject.tasks.withType<Test>().filter { it.name == "integrationTest" }
            }
            integrationTestTasks.forEach { taskToDependOn ->
                this@register.dependsOn(taskToDependOn)
            }
        }
    })
    finalizedBy("coverageReport")
}

tasks.register<JacocoReport>("coverageReport") {
    group = "reporting"
    description = "Generates a combined JaCoCo coverage report for all subprojects."

    // Ensure this task runs after all relevant test tasks have completed
    val allTestTasks = subprojects.flatMap { subproject ->
        subproject.tasks.withType<Test>().matching { it.name == "test" || it.name == "integrationTest" }
    }
    dependsOn(allTestTasks)

    // Collect execution data from all test tasks
    executionData.from(files(allTestTasks.mapNotNull { testTask ->
        // Access the JacocoTaskExtension for each test task
        testTask.extensions.findByType(JacocoTaskExtension::class.java)?.destinationFile
            ?.takeIf { it.exists() } // Only include if the file exists
    }))

    // Collect source directories from all subprojects' main source sets
    val mainSourceSets = subprojects.mapNotNull { subproject ->
        subproject.extensions.findByType(SourceSetContainer::class.java)
            ?.findByName(SourceSet.MAIN_SOURCE_SET_NAME)
    }
    sourceDirectories.from(files(mainSourceSets.map { it.allSource.srcDirs }).filter { it.exists() })
    classDirectories.from(files(mainSourceSets.map { it.output.classesDirs }).filter { it.exists() })

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }

    doLast {
        if (executionData.files.isEmpty()) {
            logger.warn("No JaCoCo execution data files found. Coverage report will be empty.")
        }
        if (sourceDirectories.files.isEmpty()) {
            logger.warn("No source directories found. Coverage report will be empty or incomplete.")
        }
        if (classDirectories.files.isEmpty()) {
            logger.warn("No class directories found. Coverage report will be empty or incomplete.")
        }
    }
}

tasks.register("generateManifests") {
    group = "generation"
    description = "Gathers pipeline data, prepares Helm values, and will eventually generate manifests."

    doLast {
        val yamlSlurper = YamlSlurper()
        val pipelineData = mutableMapOf<String, Any>(
            "functions" to mutableMapOf<String, Any>(),
            "connectors" to mutableListOf<Map<String, Any>>()
        )
        var tenant = "default-tenant" // Default value
        var namespace = "default-namespace" // Default value

        // Parse deployment/pipeline.yaml for Functions and global values
        val pipelineFile = project.file("deployment/pipeline.yaml")
        if (pipelineFile.exists()) {
            val pipelineConfig = yamlSlurper.parseText(pipelineFile.readText()) as Map<String, Any>
            (pipelineData["functions"] as MutableMap<String, Any>).putAll(pipelineConfig["functions"] as Map<String, Any>? ?: emptyMap())
            tenant = pipelineConfig["tenant"] as String? ?: tenant
            namespace = pipelineConfig["namespace"] as String? ?: namespace
        } else {
            logger.warn("deployment/pipeline.yaml not found. Using default tenant and namespace.")
        }

        // Discover and Parse Connectors
        val connectorsDir = project.file("connectors")
        if (connectorsDir.isDirectory) {
            connectorsDir.listFiles()?.forEach { connectorDir ->
                if (connectorDir.isDirectory) {
                    val connectorName = connectorDir.name
                    val connectorYamlFile = project.file("${connectorDir.path}/connector.yaml")
                    if (connectorYamlFile.exists()) {
                        try {
                            val connectorConfig = yamlSlurper.parseText(connectorYamlFile.readText()) as Map<String, Any>
                            val configFileName = connectorConfig["configFile"] as String?
                            var specificConfig: Map<String, Any>? = null
                            if (configFileName != null) {
                                val specificConfigFile = project.file("${connectorDir.path}/${configFileName}")
                                if (specificConfigFile.exists()) {
                                    specificConfig = yamlSlurper.parseText(specificConfigFile.readText()) as Map<String, Any>
                                } else {
                                    logger.warn("Connector config file ${specificConfigFile.path} not found for connector ${connectorName}.")
                                }
                            }

                            val isCustom = project.file("${connectorDir.path}/build.gradle.kts").exists() &&
                                           project.file("${connectorDir.path}/src").isDirectory

                            val connectorEntry = mutableMapOf<String, Any>(
                                "name" to connectorName,
                                "type" to (connectorConfig["type"] ?: "unknown"),
                                "image" to (connectorConfig["image"] ?: "unknown"),
                                "topic" to (connectorConfig["topic"] ?: "unknown"), // Assuming topic might be in connector.yaml
                                "config" to (specificConfig ?: emptyMap<String, Any>()),
                                "isCustom" to isCustom
                            )
                            // Add any other details from connectorConfig directly
                            connectorConfig.forEach { key, value ->
                                if (!connectorEntry.containsKey(key)) {
                                    connectorEntry[key] = value
                                }
                            }

                            (pipelineData["connectors"] as MutableList<Map<String, Any>>).add(connectorEntry)
                        } catch (e: Exception) {
                            logger.error("Error parsing connector.yaml for ${connectorName}: ${e.message}")
                        }
                    } else {
                        logger.warn("connector.yaml not found in ${connectorDir.path}")
                    }
                }
            }
        } else {
            logger.warn("Connectors directory 'connectors/' not found.")
        }

        // Output for verification
        val functionsMap = pipelineData["functions"] as Map<String, Any>
        val connectorsList = pipelineData["connectors"] as List<Map<String, Any>>
        println("Found ${functionsMap.size} functions: ${functionsMap.keys}")
        println("Found ${connectorsList.size} connectors: ${connectorsList.map { it["name"] }}")

        // Prepare helmValues
        val helmValues = mutableMapOf<String, Any>(
            "tenant" to tenant,
            "namespace" to namespace,
            "functions" to functionsMap,
            "connectors" to mutableMapOf<String, Any>()
        )

        connectorsList.forEach { connector ->
            val connectorName = connector["name"] as String
            // Create a mutable copy to avoid modifying the original pipelineData connector entries if needed elsewhere
            val connectorDetails = connector.toMutableMap()
            // The 'name' key is redundant when the connector name is the key in the map.
            // However, Helm charts might expect it, so let's keep it for now or make it configurable.
            // connectorDetails.remove("name")
            (helmValues["connectors"] as MutableMap<String, Any>)[connectorName] = connectorDetails
        }

        // Write helmValues to YAML file
        val helmValuesFile = project.file("${project.buildDir}/tmp/helm_values.yaml")
        helmValuesFile.parentFile.mkdirs() // Ensure directory exists
        helmValuesFile.text = YamlBuilder().build(helmValues)
        println("Helm values written to ${helmValuesFile.absolutePath}")
        // println("Helm values content:\n${helmValuesFile.readText()}") // Uncomment for debugging

        // Ensure build/deploy directory exists
        val deployDir = project.file("${project.buildDir}/deploy")
        deployDir.mkdirs()

        // Execute Helm to generate FunctionMesh manifest
        val functionMeshOutputFile = project.file("${deployDir.path}/functionmesh-pipeline.yaml")
        project.exec {
            executable = "helm"
            args = listOf(
                "template",
                "my-fm-release", // Release name for templating
                project.file("deployment/helm").absolutePath,
                "--values",
                helmValuesFile.absolutePath,
                "--show-only",
                "templates/mesh/function-mesh.yaml"
            )
            standardOutput = functionMeshOutputFile.newOutputStream()
            isIgnoreExitValue = false
        }
        println("FunctionMesh manifest generated at ${functionMeshOutputFile.absolutePath}")

        // Execute Helm to generate Worker pipeline manifest
        val workerPipelineOutputFile = project.file("${deployDir.path}/worker-pipeline.yaml")
        project.exec {
            executable = "helm"
            args = listOf(
                "template",
                "my-worker-release", // Release name for templating
                project.file("deployment/helm").absolutePath,
                "--values",
                helmValuesFile.absolutePath,
                "--show-only",
                "templates/worker/registration-job.yaml"
            )
            standardOutput = workerPipelineOutputFile.newOutputStream()
            isIgnoreExitValue = false
        }
        println("Worker pipeline manifest generated at ${workerPipelineOutputFile.absolutePath}")

        // Ensure deployment/local-dev directory exists
        val localDevDir = project.file("deployment/local-dev")
        localDevDir.mkdirs()
        val bootstrapScriptFile = project.file("${localDevDir.path}/bootstrap.sh")

        // Execute Helm to generate bootstrap.sh
        project.exec {
            executable = "helm"
            args = listOf(
                "template",
                "my-bootstrap-release",
                project.file("deployment/helm").absolutePath,
                "--values",
                helmValuesFile.absolutePath,
                "--show-only",
                "templates/compose/bootstrap.sh.tpl"
            )
            standardOutput = bootstrapScriptFile.newOutputStream()
            isIgnoreExitValue = false
        }
        // Make bootstrap.sh executable
        bootstrapScriptFile.setExecutable(true, false) // true for owner executable, false for all users
        println("Bootstrap script generated at ${bootstrapScriptFile.absolutePath} and made executable.")

        println("--- generateManifests task finished ---")
    }
}

tasks.register<Exec>("composeUp") {
    group = "sandbox"
    description = "Starts local development environment using Docker Compose."
    dependsOn(tasks.named("generateManifests"))
    workingDir = project.file("deployment/local-dev")
    commandLine("docker", "compose", "up", "-d")
}

tasks.register<Exec>("composeDown") {
    group = "sandbox"
    description = "Stops local development environment and removes containers/volumes."
    workingDir = project.file("deployment/local-dev")
    commandLine("docker", "compose", "down", "--volumes")
}

tasks.register<Exec>("loadTest") {
    group = "sandbox"
    description = "Runs a load test against the local development environment."
    dependsOn("composeUp")
    val loadTestRateProvider = project.providers.gradleProperty("loadTest.rate").orElse("100000")
    commandLine = listOf("bash", "deployment/local-dev/scripts/load-test.sh", "--rate", loadTestRateProvider.get())
    workingDir = project.rootDir
}