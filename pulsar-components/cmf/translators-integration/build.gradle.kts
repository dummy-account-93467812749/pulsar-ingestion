// functions/translators/translators-integration/build.gradle.kts

plugins {
    id("com.google.cloud.tools.jib") // Jib might not be strictly necessary if this module only runs tests and doesn't produce an artifact to be containerized itself.
                                     // However, including it for consistency or future needs (e.g. a test utility image) is fine.
    id("java-library") // For test compilation and execution
}

// Apply Kotlin plugin
// plugins {
//     id("org.jetbrains.kotlin.jvm") // Already applied by root project's subprojects {} block
// }

// group = "com.acme.pulsar.functions.translators" // Inherited from root
// version = "0.1.0-SNAPSHOT" // Inherited from root

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

dependencies {
    implementation(platform(libs.pulsar.bom))
    implementation(libs.pulsar.functions.api) // For Function, Context etc. if used directly in tests, though usually comes via translator modules

    // Dependency on the new vehicle telemetry translators module
    implementation(project(":pulsar-components:cmf:vehicle-telemetry-translators"))

    // Dependencies on placeholder translator modules removed as they no longer exist.

    implementation(project(":libs")) // Common code, schemas
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)

    // Test dependencies
    testImplementation(project(":test-kit")) // For test utilities and configurations
    testImplementation(libs.pulsar.functions.local.runner.original) // For LocalRunner
    testImplementation(libs.testcontainers.pulsar)
    testImplementation(libs.pulsar.client.admin.original)
    // Common test libraries (JUnit, MockK are usually included via test-kit or root project's subprojects block)
    // testImplementation(libs.junit.jupiter.api)
    // testImplementation(libs.junit.jupiter.engine)
    // testImplementation(libs.mockk)
}

/* -------------- container image -------------- */
// Jib configuration might be optional if this module is purely for tests.
// If an image is desired (e.g., for running these integration tests in a containerized environment),
// then this configuration would be relevant. For now, it's included but might not be used.
jib {
    from.image = "eclipse-temurin:17-jre"
    // The project.name here will correctly pick up the sub-module's name "translators-integration"
    to.image   = "ghcr.io/acme/${project.name}:${project.version}"
    container.entrypoint = listOf() // Not typically needed for a test-only module unless it's runnable
    // This module likely doesn't produce a primary JAR to be added to the image for function/connector deployment.
    // If Jib tries to find a JAR and fails, or if you want an empty image, you might need to adjust.
    // For a test-only module, often `tasks.jib.enabled = false` or `tasks.jibDockerBuild.enabled = false` might be set,
    // or the plugin not applied at all if no image is ever needed.
    // Keeping it for now for structural consistency.
}

// This module will run tests, so ensure the test task is configured
tasks.test {
    useJUnitPlatform()
    // Add any specific test configurations here if needed
}

// Since this module is for integration tests, you might want to ensure
// they run as part of a specific aggregate task if not covered by `ciFull`'s logic.
// However, `ciFull` should pick up tests from all subprojects.
// The root `build.gradle.kts` already configures `integrationTest` tasks for subprojects.
// If these are to be treated as standard tests, no special configuration is needed here.
// If they need to be separated, an `integrationTest` sourceSet like in other modules could be defined.
// For now, assume they run as part of the standard `test` task in this module.
