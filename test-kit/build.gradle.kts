// test-kit/build.gradle.kts

plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    // JUnit 5 aggregator + Kotlin assertions
    api(libs.junit.jupiter)
    api(libs.kotlin.test.junit5)

    // MockK for mocking
    api(libs.mockk)

    // Pulsar Functions API (for LocalRunner.Builder, etc.)
    api(libs.pulsar.functions.api)

    // Pulsar Functions LocalRunner (original, un‑shaded)
    api(libs.pulsar.functions.local.runner)

    // Protobuf runtime (required by LocalRunner)
    api(libs.protobufJava)

    // Testcontainers for spinning up Pulsar
    api(libs.testcontainers.pulsar)

    // Pulsar client (if your tests need to produce/consume)
    api(libs.pulsar.client)
    api(libs.pulsar.client.admin) // <--- ADD THIS LINE

    // Optional: Mockito-Kotlin if you still need it
    api(libs.mockito.kotlin)
}