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

            // Standard path within project
            java.srcDir("src/integrationTest/java")
            kotlin.srcDir("src/integrationTest/kotlin")
            resources.srcDir("src/integrationTest/resources")

            // Check if this project is a connector that uses the new centralized test structure
            // Project names are like "connectors-azure-eventhub-source", "connectors-http-source" etc. from settings.gradle.kts
            // We need to extract "azure-eventhub", "http" from project.name
            val projectName = project.name
            var connectorName = ""
            if (project.path.startsWith(":connectors:")) {
                // Assuming project name format is "connectors-<connectorId>-source" or "<connectorId>-source" if group is "connectors"
                // Or if project.name is "azure-eventhub-source" when project path is ":connectors:azure-eventhub-source"
                // Let's try to be robust: remove common suffixes and prefixes if necessary.
                // A simpler way if using project.name directly from a path like :connectors:azure-eventhub-source
                // project.name would be "azure-eventhub-source"
                connectorName = projectName.removeSuffix("-source") // "azure-eventhub"
                                       .removeSuffix("-connector") // common pattern
                                       .removePrefix("pulsar-io-") // common pattern
                                       .removePrefix("connectors-") // if group not used in name

                 // If project.path is ":connectors:azure-eventhub", project.name is "azure-eventhub"
                if (project.parent?.name == "connectors" && project.name.contains("-source")){
                     connectorName = project.name.removeSuffix("-source")
                } else if (project.parent?.name == "connectors") {
                     connectorName = project.name
                }


                // Refined logic based on typical project path structure like ":connectors:azure-eventhub-source"
                if (project.path.count { it == ':' } == 2 && project.path.startsWith(":connectors:")) {
                    connectorName = project.name.removeSuffix("-source")
                }


                val centralizedTestDir = project.rootDir.resolve("connectors/test/$connectorName")
                if (centralizedTestDir.exists()) {
                    project.logger.lifecycle("Project ${project.path} is a connector. Adding centralized integration test path: ${centralizedTestDir.path} for connector name: $connectorName")
                    kotlin.srcDir(centralizedTestDir)
                    // If resources are also centralized, add:
                    // resources.srcDir(project.rootDir.resolve("connectors/test/$connectorName/resources"))
                } else {
                    project.logger.lifecycle("Project ${project.path} is a connector. Centralized test dir NOT FOUND: ${centralizedTestDir.path} for connector name: $connectorName (original project name: ${project.name})")
                }
            }
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

        val helmValues = mapOf(
            "tenant" to tenant,
            "namespace" to namespace,
            "mesh" to mapOf(
                "enabled" to true,
                "functions" to adaptedFunctionsForMesh,
                "connectors" to adaptedConnectorsForMesh
            ),
            "functions" to functionsMap,
            "connectors" to connectorEntries.associateBy { it["name"].toString() }
        )

        val valuesFile = project.buildDir.resolve("tmp/helm_values.yaml").apply { parentFile.mkdirs() }
        valuesFile.writeText(yaml.dump(helmValues))
        println("Helm values written to ${valuesFile.absolutePath}")

        val helmChartDir = project.file("deployment/helm")

        fun gen(release: String, targetSourceTemplate: String, out: File) {
            out.parentFile.mkdirs()
            val outputBaos = java.io.ByteArrayOutputStream()
            val errorBaos = java.io.ByteArrayOutputStream()
            val execResult = project.exec {
                executable = "helm"
                args = listOf("template", release, helmChartDir.absolutePath, "--values", valuesFile.absolutePath)
                standardOutput = outputBaos
                errorOutput = errorBaos
                isIgnoreExitValue = true
            }
            val fullOutput = outputBaos.toString().trim()
            val errorOutput = errorBaos.toString().trim()
            if (execResult.exitValue != 0) {
                logger.error("Helm template command failed for release '$release'. Exit code: ${execResult.exitValue}")
                logger.error("Helm stderr: $errorOutput")
            }
            val documents = mutableListOf<String>()
            val sourceCommentPrefix = "# Source: "
            fullOutput.split("---").forEach { docString ->
                val trimmedDoc = docString.trim()
                if (trimmedDoc.isNotEmpty() && trimmedDoc.startsWith(sourceCommentPrefix + targetSourceTemplate)) {
                    documents.add(trimmedDoc)
                }
            }
            if (documents.isNotEmpty()) {
                out.writeText(documents.joinToString("\n---\n") + "\n")
                println("Wrote (filtered) $targetSourceTemplate -> ${out.path}")
            } else {
                logger.warn("No documents found for source '$targetSourceTemplate' in Helm output for release '$release'. Output file '${out.path}' will be empty or not created.")
                out.writeText("")
            }
        }

        gen("fm", "pipeline-charts/charts/mesh/templates/function-mesh.yaml", project.buildDir.resolve("deploy/functionmesh-pipeline.yaml"))
        gen("wk", "pipeline-charts/charts/worker/templates/registration-job.yaml", project.buildDir.resolve("deploy/worker-pipeline.yaml"))

        val bsTemplate = helmChartDir.resolve("scripts/bootstrap-direct-admin.sh.tpl")
        val bsOut = project.buildDir.resolve("deploy/compose/bootstrap.sh")
        if (bsTemplate.exists()) {
            copy {
                from(bsTemplate.parentFile)
                include("bootstrap-direct-admin.sh.tpl")
                into(bsOut.parentFile)
                rename { "bootstrap.sh" }
            }
            bsOut.setExecutable(true)
            println("Copied bootstrap script -> ${bsOut.absolutePath}")
        } else {
            logger.warn("Bootstrap template (for copy) not found at ${bsTemplate.path}, skipping copy.")
        }
    }
}

tasks.register<Exec>("composeUp") {
    group="sandbox";
    description="Starts local dev via Docker Compose";
    dependsOn("generateManifests");
    workingDir=project.file("deployment/local-dev");
    commandLine("docker","compose","up","-d")
}

tasks.register<Exec>("composeDown") {
    group="sandbox";
    description="Stops and removes containers";
    workingDir=project.file("deployment/local-dev");
    commandLine("docker","compose","down","--volumes")
}
tasks.register<Exec>("loadTest") {
    group="sandbox";
    description="Runs load test";
    dependsOn("composeUp");
    val rate=project.providers.gradleProperty("loadTest.rate").orElse("100000");
    commandLine("bash","deployment/local-dev/scripts/load-test.sh","--rate",rate.get())
}