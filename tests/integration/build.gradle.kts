// tests/integration/build.gradle.kts
plugins {
    id("java-library") // Fine for test modules
    // Kotlin and Ktlint are applied from the root project's subprojects block
}

// Define a custom source set for integration tests
sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
        runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
        java.srcDir("src/integrationTest/java")
        kotlin.srcDir("src/integrationTest/kotlin")
        resources.srcDir("src/integrationTest/resources")
    }
}

// Create a task to run integration tests
val integrationTest by tasks.registering(Test::class) {
    description = "Runs integration tests."
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    mustRunAfter(tasks.test) // Run after unit tests
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// Make 'check' task depend on integration tests
tasks.check {
    dependsOn(integrationTest)
}

dependencies {
    // Dependencies needed for integration tests
    "integrationTestImplementation"(project(":functions:common-models"))
    "integrationTestImplementation"(project(":functions:acme-enrich")) // If you test the function directly
    "integrationTestImplementation"(libs.pulsar.client.original) // Full client for testing
    "integrationTestImplementation"(libs.slf4j.api)
    "integrationTestRuntimeOnly"(libs.logback.classic)

    // Testcontainers
    "integrationTestImplementation"(platform(libs.testcontainers.bom)) // BOM
    "integrationTestImplementation"(libs.testcontainers.junit.jupiter)
    "integrationTestImplementation"(libs.testcontainers.pulsar)

    // Regular test dependencies for integration tests (JUnit, MockK)
    "integrationTestImplementation"(libs.junit.jupiter.api)
    "integrationTestImplementation"(libs.junit.jupiter.params)
    "integrationTestRuntimeOnly"(libs.junit.jupiter.engine)
    "integrationTestImplementation"(libs.mockk)
}
