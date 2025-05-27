plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    implementation(platform(libs.pulsar.bom))
    implementation(libs.pulsar.io.core)
    implementation(libs.kafka.clients)

    // Jackson for YAML/JSON configuration
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.jackson.module.kotlin)

    // Testing
    testImplementation(project(":test-kit"))
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.engine)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.kotlin.test.junit5) // For Kotlin specific assertions with JUnit 5
    testImplementation(libs.testng) // If any TestNG specific features are needed, or for consistency
}
