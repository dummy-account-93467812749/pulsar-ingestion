plugins {
    kotlin("jvm")
    id("com.github.johnrengelman.shadow") // Using ID directly as per libs.versions.toml's plugin definition
                                         // plugins.shadow.id = "com.github.johnrengelman.shadow"
}

dependencies {
    implementation(project(":functions:common-models"))
    implementation(libs.kotlin.stdlib)

    implementation(libs.pulsar.client)

    // Logging
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)
}
