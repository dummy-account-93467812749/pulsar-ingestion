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
    implementation(platform(libs.pulsar.bom))
    implementation(libs.pulsar.functions.api)
    implementation(libs.pulsar.functions.local.runner.original)
    implementation(project(":common"))
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.databind)     

    // Test dependencies:
    // 1. Rely on test-kit for common test utilities, including Pulsar client/admin/localrunner and base JUnit/Testcontainers.
    testImplementation(project(":test-kit"))

    // 2. Explicitly include testcontainers-pulsar. This is the key dependency that
    //    should bring in JUnit Jupiter API and Testcontainers' JUnit 5 support transitively.
    //    Even though test-kit provides this as an 'api' dependency, having it here
    //    explicitly mirrors the working 'translator' setup and ensures it's considered.
    testImplementation(libs.testcontainers.pulsar)

    // 3. If your splitter tests directly use Jackson for test-specific serialization/deserialization
    //    (and it's not just for the objects under test which would be covered by 'implementation'),
    //    keep this. It's also an 'implementation' dependency, so it's often redundant here
    //    unless a specific test-scope version or direct test utility usage is intended.
    testImplementation(libs.jackson.module.kotlin)

    // 4. Explicitly include pulsar-client-admin-original, similar to 'translator'.
    //    Again, 'test-kit' provides this as 'api', but mirroring 'translator' might help.
    testImplementation(libs.pulsar.client.admin.original)
    
    // REMOVED: testImplementation(libs.pulsar.functions.local.runner.original)
    // This was the main difference from translator's explicit test dependencies.
    // It's provided by test-kit via 'api', so this explicit declaration is redundant
    // and a potential, albeit unlikely, source of subtle conflict.

    // These are for the separate 'integrationTest' source set, not for the 'test' task.
    // integrationTestImplementation(project(":test-kit"))
    // testImplementation(libs.testcontainers.pulsar) // This was duplicated from above, removed outer one.
    // testImplementation(libs.pulsar.functions.local.runner.original) // Duplicated
    // testImplementation(libs.pulsar.client.admin.original) // Duplicated
}

tasks.shadowJar {
    archiveClassifier.set("")
    manifest { attributes["Main-Class"] = "com.example.pulsar.functions.routing.EventTypeSplitter" }
    isZip64 = true
}

tasks.register<Zip>("makeNar") {
    dependsOn(tasks.shadowJar)
    dependsOn(tasks.named("jar")) // <<< ADD THIS LINE

    archiveFileName.set("${project.name}-${project.version}.nar")
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
