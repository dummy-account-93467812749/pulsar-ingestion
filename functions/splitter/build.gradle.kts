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
    implementation(project(":common"))
    implementation(libs.jackson.module.kotlin)

    testImplementation(project(":test-kit"))
    testImplementation(libs.testcontainers.pulsar)
    testImplementation(libs.jackson.module.kotlin)
    testImplementation(libs.pulsar.client.admin.original)
}

tasks.shadowJar {
    archiveClassifier.set("")
    manifest { attributes["Main-Class"] = "com.example.pulsar.functions.routing.EventTypeSplitter" }
    isZip64 = true
}

tasks.register<Zip>("makeNar") {
    dependsOn(tasks.shadowJar)
    dependsOn(tasks.named("jar")) // <<< ADD THIS LINE

    archiveFileName.set("${project.name}.nar")
    destinationDirectory.set(project.layout.buildDirectory.dir("libs"))
    isZip64 = true
    from(zipTree(tasks.shadowJar.get().archiveFile)) {
        // Optional: exclude unnecessary files from the NAR if shadowJar includes too much
        // exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
}

jib {
    from.image = "eclipse-temurin:17-jre"
    to.image   = "ghcr.io/acme/${project.name}:${project.version}"
    container.entrypoint = listOf()
}

tasks.named("assemble") {
    dependsOn(tasks.named("makeNar"))
}
