plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    implementation(project(":common")) // For CommonEvent
    implementation("org.apache.pulsar:pulsar-client:4.0.0")
    implementation("org.apache.pulsar:pulsar-functions-api:4.0.0")
    // For Kotlin stdlib, if not brought by "kotlin.jvm" plugin or other dependencies transitively
    implementation(kotlin("stdlib-jdk8"))


    testImplementation("org.apache.pulsar:pulsar-functions-local-runner:4.0.0")
    // JUnit BOM is in the root build.gradle.kts, so direct dependencies are fine
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.mockk:mockk:1.13.0")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
