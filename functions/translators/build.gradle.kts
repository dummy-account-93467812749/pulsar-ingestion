// functions/translators/build.gradle.kts

plugins {
    id("com.github.johnrengelman.shadow")
    id("com.google.cloud.tools.jib")
}

dependencies {
  implementation(platform(libs.pulsar.bom))
  // testImplementation(platform(libs.pulsar.bom)) // This is fine, but often redundant if already declared without config scope

  implementation("org.apache.pulsar:pulsar-functions-api")

  // ⚠️ ONLY pull in the ORIGINAL (un-shaded) runner
  implementation("org.apache.pulsar:pulsar-functions-local-runner-original")

  // your code
  implementation(project(":common"))
  // let Pulsar BOM manage the non-shaded Jackson
  implementation("com.fasterxml.jackson.core:jackson-databind")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

  testImplementation(project(":test-kit"))
  testImplementation(libs.testcontainers.pulsar) {
      exclude(group = "org.apache.pulsar", module = "pulsar-client-admin") // <-- CORRECTED SYNTAX
  }
  // Explicitly add the -original version to the test classpath.
  // The version will be managed by the pulsar.bom.
  testImplementation("org.apache.pulsar:pulsar-client-admin-original")
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
    from.image = "eclipse-temurin:21-jre" // Ensure this matches your Kotlin JVM target (e.g., JVM_21)
    to.image   = "ghcr.io/acme/${project.name}:${project.version}" // This seems appropriate for 'translators'
    container.entrypoint = listOf()                    // Function Mesh handles cmd
    // Consider setting container.appRoot or other jib configurations
}