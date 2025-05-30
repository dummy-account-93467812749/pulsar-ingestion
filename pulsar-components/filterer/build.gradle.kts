// functions/splitter/build.gradle.kts

plugins {
    // id("org.jetbrains.kotlin.jvm") // Applied by root
    id("com.github.johnrengelman.shadow")
    id("com.google.cloud.tools.jib")
}

jacoco {
    toolVersion = libs.versions.jacoco.get() // Should resolve to "0.8.12"
}

dependencies {
    //implementation(platform(libs.pulsar.bom))
    implementation(libs.pulsar.functions.api)
    //implementation(libs.pulsar.functions.local.runner.original)
    implementation(project(":libs"))
    implementation(libs.jackson.module.kotlin)

    testImplementation(project(":test-kit"))
    testImplementation(libs.testcontainers.pulsar)
    testImplementation(libs.jackson.module.kotlin)
    testImplementation(libs.pulsar.client.admin.original)
}

jib {
    from.image = "eclipse-temurin:17-jre"
    to.image   = "ghcr.io/acme/${project.name}:${project.version}"
    container.entrypoint = listOf()
}
