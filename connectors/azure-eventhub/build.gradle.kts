plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    implementation(platform(libs.pulsar.bom))
    implementation(libs.pulsar.io.core)

    // Azure SDK
    implementation(libs.azure.messaging.eventhubs)
    implementation(libs.azure.identity) // For DefaultAzureCredential, etc.
    implementation(libs.azure.storage.blob) // For BlobCheckpointStore

    // Jackson for YAML/JSON configuration
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.jackson.module.kotlin)

    // Logging (SLF4J API is already a dependency of pulsar-io-core, add specific binding if needed)
    // runtimeOnly(libs.slf4j.simple) // Or any other SLF4J binding

    // Testing
    testImplementation(project(":test-kit"))
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.engine)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers.azure) // For Azurite
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.testng)
}
