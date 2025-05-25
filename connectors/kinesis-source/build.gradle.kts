plugins {
    id("org.jetbrains.kotlin.jvm") version "1.9.20"
}

dependencies {
    implementation(project(":common"))
    implementation("org.apache.pulsar:pulsar-io-core:3.1.0")
    implementation("software.amazon.awssdk:kinesis:2.20.100")
}
