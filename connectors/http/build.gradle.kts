plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    implementation(platform(libs.pulsar.bom))
    implementation(libs.pulsar.io.core)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.jackson.module.kotlin)

    // Ktor client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio) // CIO engine for Ktor
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    testImplementation(project(":test-kit"))
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlin.test.junit5) // For JUnit 5 based tests if any
    testImplementation(libs.testng) // For TestNG based tests

    // Ktor server for integration tests (already added in previous session)
    testImplementation(libs.ktor.server.core)
    testImplementation(libs.ktor.server.netty)
    testImplementation(libs.ktor.server.content.negotiation)
    testImplementation(libs.ktor.serialization.kotlinx.json)
}
