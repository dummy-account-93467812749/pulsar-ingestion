// functions/translators/shipment-status-translator/build.gradle.kts

plugins {
    id("com.google.cloud.tools.jib")
    alias(libs.plugins.shadow) // Added shadow plugin
    // Add java-library for standard JAR output capabilities if not already implicitly provided
    // For Pulsar functions, a simple JAR is often packaged into a NAR or used by Jib directly.
    // If the project structure implies standard library practices, uncomment:
    // id("java-library") 
}

// Apply Kotlin plugin if not inherited from parent or if this is a standalone module setup
// plugins {
//     id("org.jetbrains.kotlin.jvm") version "your_kotlin_version" // Or use alias(libs.plugins.kotlin.jvm) if defined
// }


// Ensure group and version are set, or inherit from parent project if applicable
// group = "com.acme.pulsar.functions.translators" // Or as appropriate
// version = "0.1.0-SNAPSHOT" // Or as appropriate

// Configure the standard JAR task
tasks.jar {
    archiveFileName.set("shipment-status-translator.jar")
}

jacoco {
    toolVersion = libs.versions.jacoco.get() 
}

dependencies {
  implementation(platform(libs.pulsar.bom))
  implementation(libs.pulsar.functions.api)

  // Changed from implementation to testImplementation for local runner
  testImplementation(libs.pulsar.functions.local.runner.original)

  implementation(project(":libs")) // Assumes :common module provides necessary shared code

  implementation(libs.jackson.databind)
  implementation(libs.jackson.module.kotlin)

  testImplementation(project(":test-kit")) // For test utilities and configurations
  testImplementation(libs.testcontainers.pulsar)
  testImplementation(libs.pulsar.client.admin.original)
  // shadowJar configuration should not affect test dependencies directly
  // testImplementation project(":test-kit") is already excluded by exclude(dependency(":test-kit")) in shadowJar
}

/* -------------- container image -------------- */
jib {
    from.image = "eclipse-temurin:17-jre"
    // The project.name here will correctly pick up the sub-module's name
    to.image   = "ghcr.io/acme/${project.name}:${project.version}" 
    container.entrypoint = listOf() // Function Mesh handles cmd
    // Example: If your function JAR needs to be placed in a specific directory within the image for Pulsar to find it.
    // container.appRoot = "/pulsar/functions" // Adjust as needed by your base image or Pulsar runtime
    // If your JAR is the main artifact and needs to be added explicitly (Jib usually finds it):
    // extraDirectories.paths {
    //   path {
    //     from = layout.buildDirectory.dir("libs") // Or wherever the JAR is built
    //     into = "/pulsar/functions" // Target directory in the image
    //     includes += ["*.jar"]
    //   }
    // }
}

// Ensure the standard JAR task is configured if not using shadowJar
// tasks.jar { // This is now configured above
//   // You can configure manifest attributes or other JAR properties here if needed
// }

// The shadowJar task, by default, is not part of the standard `assemble` or `build` lifecycle
// for the `java` plugin (which provides the `jar` task).
// We need to ensure it's run. Often, just having it configured is enough if other tasks
// depend on its output, or if we explicitly call it.
// For the `bundleForDeploy` use case, that task would typically depend on the output of `shadowJar`.
// If `shadowJar` is not run by `build`, we might need:
// tasks.assemble.get().dependsOn(tasks.shadowJar)
// However, it's better if the consuming task (e.g. a hypothetical copy task for deployment)
// explicitly depends on `shadowJar.archiveFile`.
// For now, we assume the build system or a subsequent task will pick up the .nar file.
