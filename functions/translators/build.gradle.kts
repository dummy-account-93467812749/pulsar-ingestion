// functions/translators/build.gradle.kts

plugins {
    id("com.github.johnrengelman.shadow")
    id("com.google.cloud.tools.jib")
}

jacoco {
    toolVersion = libs.versions.jacoco.get() // Should resolve to "0.8.12"
}

dependencies {
  implementation(platform(libs.pulsar.bom))
  // testImplementation(platform(libs.pulsar.bom)) // This is fine, but often redundant if already declared without config scope

  implementation(libs.pulsar.functions.api) // Use catalog

  // ⚠️ If pulsar-functions-local-runner-original is ONLY for testing, this should be testImplementation
  // or preferably be provided by test-kit. Including it as 'implementation' will add it to your NAR via shadowJar.
  // For a standard Pulsar Function, this is usually not needed in the NAR.
  // However, sticking to your original scoping for now, just using the catalog alias:
  implementation(libs.pulsar.functions.local.runner.original) // Use catalog

  // your code
  implementation(project(":common"))

  // Let Pulsar BOM manage the non-shaded Jackson versions.
  // These are likely transitive dependencies of Pulsar client libs,
  // but declaring them explicitly is fine if your function code uses them directly.
  implementation(libs.jackson.databind)        // Use catalog
  implementation(libs.jackson.module.kotlin)  // Use catalog

  // Test dependencies
  testImplementation(project(":test-kit")) // This is key for bringing in correct -original pulsar libs for tests

  testImplementation(libs.testcontainers.pulsar) {
      // REMOVED: exclude(group = "org.apache.pulsar", module = "pulsar-client-admin")
      // Rationale: testcontainers-pulsar typically depends on pulsar-client-admin-original.
      // The BOM and your explicit testImplementation(libs.pulsar.client.admin.original) below
      // should correctly resolve to the unshaded admin client. Removing this simplifies.

      // REMOVED: exclude(group = "org.apache.bookkeeper", module = "bookkeeper-server")
      // REMOVED: exclude(group = "org.apache.bookkeeper", module = "circe-checksum")
      // Rationale: The circe-checksum NoSuchMethodError is usually due to a shaded pulsar-client.jar
      // on the test classpath that bundles an old circe. Using -original artifacts via test-kit
      // and BOM should resolve this by putting the correct standalone circe-checksum.jar on the classpath.
      // Excluding bookkeeper components is unlikely to fix this specific client-side issue and might break the testcontainer.
  }

  // Explicitly add the -original admin client to the test classpath.
  // This ensures the unshaded version is used for tests if there's any ambiguity.
  // The version will be managed by the pulsar.bom.
  testImplementation(libs.pulsar.client.admin.original) // Use catalog
}

tasks.register<Zip>("makeNar") {
    dependsOn(tasks.shadowJar)
    archiveFileName.set("${project.name}-${project.version}.nar")
    from(zipTree(tasks.shadowJar.get().archiveFile)) {
        // Optional: exclude unnecessary files from the NAR if shadowJar includes too much
        // exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
}

/* -------------- container image -------------- */
jib {
    from.image = "eclipse-temurin:23-jre" // Ensure this matches your Kotlin JVM target (e.g., JVM_23 as per root project)
    to.image   = "ghcr.io/acme/${project.name}:${project.version}" // This seems appropriate for 'translators'
    container.entrypoint = listOf()                    // Function Mesh handles cmd
    // Consider setting container.appRoot or other jib configurations
}