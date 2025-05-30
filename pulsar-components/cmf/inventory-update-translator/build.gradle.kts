// functions/translators/inventory-update-translator/build.gradle.kts

plugins {
    id("com.google.cloud.tools.jib")
    alias(libs.plugins.shadow)
}

jacoco {
    toolVersion = libs.versions.jacoco.get() 
}

dependencies {
  //implementation(platform(libs.pulsar.bom))
  implementation(libs.pulsar.functions.api)

  // Changed from implementation to testImplementation for local runner
  testImplementation(libs.pulsar.functions.local.runner.original)

  implementation(project(":libs")) // Assumes :common module provides necessary shared code

  implementation(libs.jackson.module.kotlin)

  testImplementation(project(":test-kit")) // For test utilities and configurations
  testImplementation(libs.testcontainers.pulsar)
  testImplementation(libs.pulsar.client.admin.original)
  // shadowJar configuration should not affect test dependencies directly
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


tasks.named("assemble") {
    dependsOn(tasks.named("makeNar"))
}
// tasks.jar is now configured above.
// The shadowJar task will be available.
