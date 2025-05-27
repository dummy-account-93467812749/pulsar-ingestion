// Root build.gradle.kts
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.SourceSet
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.api.execution.TaskExecutionGraph
import org.gradle.kotlin.dsl.closureOf // May be needed explicitly

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.jib) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.ben.manes.versions)

    id("jacoco")
}

/* ---------- global defaults ---------- */
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "jacoco")
    apply(plugin = "com.diffplug.spotless")

    group = "com.acme.pulsar"
    version = "0.1.0-SNAPSHOT"

    repositories { mavenCentral() }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            ktlint(libs.versions.ktlintCli.get()) // Corrected to use ktlintCli
        }
    }
}

/* ---------- convenience tasks ---------- */
tasks.register("ciFast") {
    group = "verification"
    description = "Runs Spotless checks and builds (including unit tests)."
    dependsOn("spotlessCheck", "build")
}

tasks.register("ciFull") {
    group = "verification"
    description = "Runs a full CI cycle: build, integration tests, and coverage report."
    dependsOn("build")

    gradle.taskGraph.whenReady(closureOf<TaskExecutionGraph> {
        // 'this' inside closureOf refers to the TaskExecutionGraph instance
        // 'this@register' refers to the 'ciFull' task being registered.
        if (this.hasTask(this@register.path)) {
            val integrationTestTasks = subprojects.flatMap { subproject ->
                subproject.tasks.withType<Test>().filter { it.name == "integrationTest" }
            }
            integrationTestTasks.forEach { taskToDependOn ->
                this@register.dependsOn(taskToDependOn) // Add dependency to the ciFull task
            }
        }
    })
    finalizedBy("coverageReport")
}

// ... (JacocoReport, composeUp, composeDown, loadTest tasks remain the same from previous correct version)
tasks.register("coverageReport", JacocoReport::class) {
    group = "reporting"
    description = "Generates a combined JaCoCo coverage report for all subprojects."

    val relevantTestTasks = subprojects.flatMap { subproject ->
        listOfNotNull(
            subproject.tasks.findByName("test"),
            subproject.tasks.findByName("integrationTest")
        ).filterIsInstance<Test>()
    }
    dependsOn(relevantTestTasks)

    executionData.from(files(relevantTestTasks.mapNotNull { testTask ->
        testTask.extensions.findByType(JacocoTaskExtension::class.java)?.destinationFile
    }))

    val mainSourceSets = subprojects.mapNotNull { subproject ->
        subproject.extensions.findByType(SourceSetContainer::class.java)
            ?.findByName(SourceSet.MAIN_SOURCE_SET_NAME)
    }
    sourceDirectories.from(mainSourceSets.map { it.allSource.asPath })
    classDirectories.from(mainSourceSets.map { it.output.asPath })

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

tasks.register<Exec>("composeUp") {
    group = "sandbox"
    workingDir = file("local-dev")
    commandLine("docker", "compose", "up", "-d")
}

tasks.register<Exec>("composeDown") {
    group = "sandbox"
    workingDir = file("local-dev")
    commandLine("docker", "compose", "down", "--volumes")
}

tasks.register<Exec>("loadTest") {
    group = "sandbox"
    dependsOn("composeUp")
    val loadTestRate = project.findProperty("loadTest.rate")?.toString() ?: "100000"
    commandLine("bash", "local-dev/scripts/load-test.sh", "--rate", loadTestRate)
}