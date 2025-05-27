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

    // Pulsar Functions LocalRunner (original, un-shaded)
    // api(libs.pulsar.functions.local.runner) // <-- FROM: Shaded version
    api(libs.pulsar.functions.local.runner.original) // <-- TO: Original version

    // Protobuf runtime (required by LocalRunner original)
    api(libs.protobufJava)

    // Testcontainers for spinning up Pulsar
    api(libs.testcontainers.pulsar)

    // Pulsar client (use original for tests)
    // api(libs.pulsar.client) // <-- FROM: Shaded version (if it was this before, now handled by your previous change)
    api(libs.pulsar.client.original) // <-- TO: Original version (Confirm this is what you have from your description)

    // Pulsar client admin (original, un-shaded)
    api(libs.pulsar.client.admin.original) // This was already correct

    // Optional: Mockito-Kotlin if you still need it
    api(libs.mockito.kotlin)
}