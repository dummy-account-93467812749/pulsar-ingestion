// functions/splitter/build.gradle.kts

plugins {
    // id("org.jetbrains.kotlin.jvm") // Applied by root
    id("com.github.johnrengelman.shadow")
    id("com.google.cloud.tools.jib")
}

dependencies {
    implementation(platform(libs.pulsar.bom))
    implementation("org.apache.pulsar:pulsar-functions-api")
    implementation(project(":common"))
    implementation(libs.jackson.module.kotlin)

    testImplementation(project(":test-kit"))
    testImplementation(libs.jackson.module.kotlin)

    integrationTestImplementation(project(":test-kit"))
    integrationTestImplementation(libs.testcontainers.pulsar)
    integrationTestImplementation(libs.pulsar.functions.local.runner.original)
}

tasks.shadowJar {
    archiveClassifier.set("")
    manifest { attributes["Main-Class"] = "com.example.pulsar.functions.routing.EventTypeSplitter" }
}

tasks.register<Zip>("makeNar") {
    dependsOn(tasks.shadowJar)
    archiveFileName.set("${project.name}-${project.version}.nar")
    from(zipTree(tasks.shadowJar.get().archiveFile))
}

jib {
    from.image = "eclipse-temurin:21-jre"
    to.image   = "ghcr.io/example-pulsar/${project.name}:${project.version}"
    container.entrypoint = listOf()
}