import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java-test-fixtures")
    kotlin("jvm") // Apply Kotlin for test fixtures written in Kotlin
    // If you have a common 'kotlin-library' convention plugin that sets up
    // Kotlin compilation options, Detekt, Ktlint, etc., you might consider
    // applying it here too, or ensuring its configurations apply to testFixtures.
    // e.g., id("your.convention.kotlin-library")
}

// Configure Kotlin options specifically for test fixtures sources.
// This ensures that even if a broader Kotlin convention plugin (like kotlin-library)
// is not applied or doesn't fully configure test fixture compilation tasks,
// these sources still get a defined JVM target.
tasks.withType<KotlinCompile> {
    // Check if the task is for compiling testFixtures (e.g., compileTestFixturesKotlin)
    if (name.contains("TestFixtures", ignoreCase = true)) {
        kotlinOptions {
            jvmTarget = "17"
            // For test fixtures, you might have less strict compiler arguments
            // compared to main source code. For example, explicit API mode might be disabled.
            // freeCompilerArgs = freeCompilerArgs + listOf("-Xjsr305=strict") // Example
        }
    }
}

dependencies {
    // Common dependencies for test fixtures.
    // These are available to other modules using `testFixtures(project(...))`
    // and are used to compile the test fixtures themselves.
    "testFixturesImplementation"(libs.kotlin.stdlib)
    "testFixturesImplementation"(libs.junit.jupiter.api)
    "testFixturesImplementation"(libs.mockk)

    // Test fixtures can also depend on the main source set of their own project:
    // "testFixturesImplementation"(project.configurations.getByName("implementation").dependencies)
    // However, this is often implicit or handled by `java-test-fixtures` plugin.

    // Example of a project dependency for test fixtures, if needed:
    // "testFixturesImplementation"(project(":core:common"))
}
