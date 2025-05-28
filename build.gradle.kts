import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.SourceSet
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.api.execution.TaskExecutionGraph
import org.gradle.kotlin.dsl.closureOf
import org.yaml.snakeyaml.Yaml
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File // Added for File type hint, though not strictly necessary for the change

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

        // Load or initialize functions map
        @Suppress("UNCHECKED_CAST")
        val functionsMap: MutableMap<String, Any> = if (pipelineFile.exists()) {
            val cfg = yaml.load<Map<String, Any>>(pipelineFile.readText())
            (cfg["functions"] as? Map<String, Any>)?.toMutableMap() ?: mutableMapOf()
        } else {
            logger.warn("deployment/pipeline.yaml not found. Using defaults.")
            mutableMapOf()
        }

        // Read tenant & namespace
        val rootCfg = if (pipelineFile.exists()) yaml.load<Map<String, Any>>(pipelineFile.readText()) else emptyMap<String, Any>()
        val tenant = rootCfg["tenant"] as? String ?: "default-tenant"
        val namespace = rootCfg["namespace"] as? String ?: "default-namespace"

        // Discover connectors
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

        // Build Helm values file
        val helmValues = mapOf(
            "tenant" to tenant,
            "namespace" to namespace,
            "functions" to functionsMap,
            "connectors" to connectorEntries.associateBy { it["name"].toString() }
        )

        val valuesFile = project.buildDir
            .resolve("tmp/helm_values.yaml")
            .apply { parentFile.mkdirs() }
        valuesFile.writeText(yaml.dump(helmValues))

        println("Helm values written to ${valuesFile.absolutePath}")

        // Path to Helm chart directory
        val helmChartDir = project.file("deployment/helm")

        // Helper for templating k8s YAML via Helm
        fun gen(release: String, chart: String, out: File) {
            out.parentFile.mkdirs()

            project.exec {
                executable = "helm"
                args = listOf(
                    "template",
                    release,
                    helmChartDir.absolutePath,
                    "--values",
                    valuesFile.absolutePath,
                    "--show-only",
                    chart
                )
                standardOutput = out.outputStream()
            }

            println("Wrote $chart -> ${out.path}")
        }

        // Generate k8s manifests
        gen(
            release = "fm",
            chart   = "mesh/templates/function-mesh.yaml", // MODIFIED HERE
            out     = project.buildDir.resolve("deploy/functionmesh-pipeline.yaml")
        )

        gen(
            release = "wk",
            chart   = "worker/templates/registration-job.yaml", // MODIFIED HERE
            out     = project.buildDir.resolve("deploy/worker-pipeline.yaml")
        )

        // Copy bootstrap script as-is
        // Note: This copies from the PARENT chart's templates/compose directory.
        // Ensure this is the intended bootstrap script.
        // The original error was related to deployment/helm/charts/compose/templates/bootstrap.sh.tpl
        // which you've renamed to _bootstrap.sh.tpl
        val bsTemplate = helmChartDir.resolve("templates/compose/bootstrap.sh.tpl")
        val bsOut      = project.buildDir.resolve("deploy/compose/bootstrap.sh")

        // Check if bsTemplate actually exists before attempting to copy, to avoid build failure if it was cleaned up
        if (bsTemplate.exists()) {
            copy {
                from(bsTemplate.parentFile)
                include(bsTemplate.name)
                into(bsOut.parentFile)
                rename { "bootstrap.sh" } // Renames bsTemplate.name to "bootstrap.sh" in the destination
            }
            bsOut.setExecutable(true)
            println("Copied bootstrap script -> ${bsOut.absolutePath}")
        } else {
            logger.warn("Bootstrap template not found at ${bsTemplate.path}, skipping copy.")
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