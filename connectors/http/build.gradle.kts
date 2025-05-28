plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    implementation(platform(libs.pulsar.bom))
    implementation(libs.pulsar.io.core)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.jackson.module.kotlin)

    // Ktor client - These are no longer needed for the main source as it's now a config-only connector
    // implementation(libs.ktor.client.core)
    // implementation(libs.ktor.client.cio) // CIO engine for Ktor
    // implementation(libs.ktor.client.content.negotiation)
    // implementation(libs.ktor.serialization.kotlinx.json)

    // Pulsar client and admin for testing
    testImplementation(platform(libs.pulsar.bom)) // Ensure versions align with BOM
    testImplementation("org.apache.pulsar:pulsar-client")
    testImplementation("org.apache.pulsar:pulsar-client-admin-original")

    // Testcontainers
    testImplementation("org.testcontainers:pulsar:1.19.3") // Explicit Testcontainers for Pulsar

    testImplementation(project(":test-kit"))
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlin.test.junit5) // For JUnit 5 based tests if any
    testImplementation(libs.testng) // For TestNG based tests (Consider removing if only JUnit5 is used)

    // Ktor server for integration tests (already added in previous session)
    // These seem to be for *running* a Ktor server in tests, not strictly needed if we only use Ktor *client*.
    // Keeping them for now as they might be used by other tests or test-kit.
    testImplementation(libs.ktor.server.core)
    testImplementation(libs.ktor.server.netty)
    testImplementation(libs.ktor.server.content.negotiation)
    testImplementation(libs.ktor.serialization.kotlinx.json)

    // SLF4J binding for test logging
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.7") // Example version
}
