// Root build.gradle.kts
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.SourceSet
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.api.execution.TaskExecutionGraph // Import for TaskExecutionGraph
import org.gradle.kotlin.dsl.closureOf            // Import for closureOf

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
    description = "Generates functionmesh-pipeline.yaml using Helm with absolute chart path from project root."

    val buildLayout = project.layout
    val deployDirProperty = buildLayout.buildDirectory.dir("deploy")
    val chartDirFile = project.file("deployment/helm")
    val valuesFile = project.file("deployment/pipeline.yaml")

    val functionMeshOutputProperty = deployDirProperty.get().file("functionmesh-pipeline.yaml")
    outputs.file(functionMeshOutputProperty)

    doFirst {
        deployDirProperty.get().asFile.mkdirs()
    }

    doLast {
        // println("--- Attempting to Generate functionmesh-pipeline.yaml ---") // Removed for cleaner output
        project.exec {
            executable("helm")
            args(
                "template",
                "my-test-release",
                chartDirFile.absolutePath,
                "--show-only", "templates/mesh/function-mesh.yaml",
                "--values", valuesFile.absolutePath
            )
            standardOutput = functionMeshOutputProperty.asFile.outputStream()
            isIgnoreExitValue = false
        }

        val generatedFile = functionMeshOutputProperty.asFile
        // if (generatedFile.exists() && generatedFile.length() > 0) { // Removed for cleaner output
        //     println("--- functionmesh-pipeline.yaml was generated successfully. ---")
        // } else if (generatedFile.exists()) {
        //     println("--- functionmesh-pipeline.yaml was generated but is empty. ---")
        // } else {
        //     println("--- functionmesh-pipeline.yaml was NOT generated. ---")
        // }
        // println("--- generateManifests task finished ---") // Removed for cleaner output
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