plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.20"
}

dependencies {
    implementation(project(":common")) // For CommonEvent
    implementation("org.apache.pulsar:pulsar-client:4.0.0")
    implementation("org.apache.pulsar:pulsar-functions-api:4.0.0")
    // For Kotlin stdlib, if not brought by "kotlin.jvm" plugin or other dependencies transitively
    implementation(kotlin("stdlib-jdk8"))

    // Jackson for JSON processing
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")

    // Kotlinx Serialization for JSON processing
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    testImplementation("org.apache.pulsar:pulsar-functions-local-runner:4.0.0")
    // JUnit BOM is in the root build.gradle.kts, so direct dependencies are fine
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.mockk:mockk:1.13.10") // Updated version
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.named<Test>("test") {
    enabled = false
}

sourceSets.test.get().kotlin.exclude("**/TranslatorsIntegrationTest.kt")
