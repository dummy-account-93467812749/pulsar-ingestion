plugins {
    id("org.jetbrains.kotlin.jvm") version "1.9.20"
}

dependencies {
    implementation(project(":common"))
    implementation("org.apache.pulsar:pulsar-functions-api:3.1.0")
}
