// Apply conventions
apply(from = "${rootDir}/gradle/convention/kotlin-library.gradle.kts")
apply(from = "${rootDir}/gradle/convention/detekt.gradle.kts")
apply(from = "${rootDir}/gradle/convention/ktlint.gradle.kts")

// Define a custom source set for integration tests (existing)
sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
        runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
        java.srcDir("src/integrationTest/java") // Keep if Java sources are used
        kotlin.srcDir("src/integrationTest/kotlin")
        resources.srcDir("src/integrationTest/resources")
    }
}

// Create a task to run integration tests (existing)
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

// Make 'check' task depend on integration tests (existing)
tasks.check {
    dependsOn(integrationTest)
}

// Dependencies needed for integration tests (existing)
dependencies {
    "integrationTestImplementation"(project(":functions:common-models"))
    "integrationTestImplementation"(project(":functions:acme-enrich"))
    "integrationTestImplementation"(libs.pulsar.client) // Changed from pulsar.client.original to pulsar.client

    "integrationTestImplementation"(libs.slf4j.api)
    "integrationTestRuntimeOnly"(libs.logback.classic)

    // Testcontainers
    "integrationTestImplementation"(platform(libs.testcontainers.bom))
    "integrationTestImplementation"(libs.testcontainers.junit.jupiter)
    "integrationTestImplementation"(libs.testcontainers.pulsar)

    // Regular test dependencies
    "integrationTestImplementation"(libs.junit.jupiter.api)
    "integrationTestImplementation"(libs.junit.jupiter.params)
    "integrationTestRuntimeOnly"(libs.junit.jupiter.engine)
    "integrationTestImplementation"(libs.mockk)
}
