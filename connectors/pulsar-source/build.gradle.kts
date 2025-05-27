plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    implementation(platform(libs.pulsar.bom))
    implementation(libs.pulsar.io.core)
    implementation(libs.pulsar.client) // For PulsarClient, ConsumerBuilder etc.

    // Jackson for YAML/JSON configuration
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.jackson.module.kotlin)

    // Testing
    testImplementation(project(":test-kit"))
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.engine)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers.pulsar)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.testng)
}
