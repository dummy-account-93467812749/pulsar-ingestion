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

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "jacoco")
    apply(plugin = "com.diffplug.spotless")

    group = "com.acme.pulsar"
    version = "0.1.0-SNAPSHOT"

    plugins.withId("java") {
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
            configure<JacocoTaskExtension> {}
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        configure<JacocoTaskExtension> {}
    }

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            ktlint(libs.versions.ktlintCli.get()) // Corrected to use ktlintCli
            //ktlint(libs.versions.ktlint.get())
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

    // 'build' task usually depends on 'check', and 'spotlessCheck' is part of 'check'.
    // So, depending on 'build' should trigger 'spotlessCheck' appropriately.
    dependsOn("build")

    // If you need to ensure 'spotlessCheck' is explicitly in the graph and build runs after it,
    // this is more robust if the above assumption isn't true for your setup:
    // dependsOn("spotlessCheck") // Ensure spotlessCheck is considered
    // tasks.named("build").configure {
    //     mustRunAfter("spotlessCheck")
    // }
    // dependsOn(tasks.named("build")) // Then ciFast depends on build
}

tasks.register("ciFull") {
    group = "verification"
    description = "Runs a full CI cycle: build, integration tests, and coverage report."
    dependsOn("build")

    gradle.taskGraph.whenReady(closureOf<TaskExecutionGraph> {
        // 'this' inside closureOf refers to the TaskExecutionGraph instance
        if (this.hasTask(this@register.path)) { // 'this@register' refers to the 'ciFull' task being registered.
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
    val allTestTasks = subprojects.flatMap { subproject ->
        subproject.tasks.withType<Test>().matching { it.name == "test" || it.name == "integrationTest" }
    }
    dependsOn(allTestTasks)
    executionData.from(files(allTestTasks.mapNotNull { testTask ->
        testTask.extensions.findByType(JacocoTaskExtension::class.java)?.destinationFile
            ?.takeIf { it.exists() }
    }))
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

tasks.register<Exec>("composeUp") {
    group = "sandbox"
    description = "Starts local development environment using Docker Compose."
    workingDir = file("local-dev")
    commandLine("docker", "compose", "up", "-d")
}

tasks.register<Exec>("composeDown") {
    group = "sandbox"
    description = "Stops local development environment and removes containers/volumes."
    workingDir = file("local-dev")
    commandLine("docker", "compose", "down", "--volumes")
}

tasks.register<Exec>("loadTest") {
    group = "sandbox"
    description = "Runs a load test against the local development environment."
    dependsOn("composeUp")
    val loadTestRateProvider = project.providers.gradleProperty("loadTest.rate").orElse("100000")
    commandLine = listOf("bash", "local-dev/scripts/load-test.sh", "--rate", loadTestRateProvider.get())
    workingDir = project.rootDir
}