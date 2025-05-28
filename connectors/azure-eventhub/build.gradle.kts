plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    implementation(platform(libs.pulsar.bom))
    implementation(libs.pulsar.io.core)

    // Azure SDK - These are no longer needed for the main source as it's now a config-only connector
    // implementation(libs.azure.messaging.eventhubs)
    // implementation(libs.azure.identity) // For DefaultAzureCredential, etc.
    // implementation(libs.azure.storage.blob) // For BlobCheckpointStore

    // Jackson for YAML/JSON configuration
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.jackson.module.kotlin)

    // Logging (SLF4J API is already a dependency of pulsar-io-core, add specific binding if needed)
    // runtimeOnly(libs.slf4j.simple) // Or any other SLF4J binding

    // AMQP 1.0 Client for testing
    testImplementation("org.apache.qpid:qpid-jms-client:1.9.0") // Example version, align if project has a newer one

    // Pulsar client and admin for testing
    testImplementation(platform(libs.pulsar.bom)) // Ensure versions align with BOM
    testImplementation("org.apache.pulsar:pulsar-client")
    testImplementation("org.apache.pulsar:pulsar-client-admin-original")


    // Testing
    testImplementation(project(":test-kit"))
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.engine)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers.azure) // For Azurite
    testImplementation("org.testcontainers:pulsar:1.19.3") // Explicit Testcontainers for Pulsar
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.testng) // Consider if this is needed or if JUnit5 is sufficient

    // SLF4J binding for test logging
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.7") // Example version
}
