plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    implementation(platform(libs.pulsar.bom)) // For Pulsar dependency versions
    implementation(libs.pulsar.io.core)      // If this connector had custom code
    implementation(libs.jackson.databind)     // For YAML config parsing if done by connector (not strictly needed for config-only)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.jackson.module.kotlin)

    // Test Dependencies
    testImplementation(project(":test-kit")) // Common test utilities, might include Testcontainers setup
    testImplementation(platform(libs.pulsar.bom)) // Align test Pulsar client versions
    testImplementation("org.apache.pulsar:pulsar-client")
    testImplementation("org.apache.pulsar:pulsar-client-admin-original") // For deploying/managing connectors

    testImplementation("org.testcontainers:pulsar")      // Specific Testcontainers for Pulsar
    testImplementation("org.testcontainers:kafka")       // Specific Testcontainers for Kafka
    testImplementation("org.apache.kafka:kafka-clients:3.5.1") // Kafka client for producer (use a recent compatible version)

    testImplementation(libs.junit.jupiter.api)    // JUnit 5 API
    testImplementation(libs.junit.jupiter.engine)   // JUnit 5 Engine
    testImplementation(libs.kotlin.test.junit5)   // Kotlin test integration for JUnit 5
    testImplementation(libs.mockk)                // If mocking is needed

    // SLF4J binding for test logging (important for seeing logs from Pulsar/Kafka clients & Testcontainers)
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.7") // Or another binding like logback-classic
}
