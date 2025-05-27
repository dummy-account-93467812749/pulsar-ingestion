// functions/translators/build.gradle.kts

plugins {
    // id("org.jetbrains.kotlin.jvm") // Applied by root project's subprojects block
    id("com.github.johnrengelman.shadow") // Keep version here or manage in root pluginManagement
    id("com.google.cloud.tools.jib")     // Keep version here or manage in root pluginManagement
}

// The 'integrationTest' sourceSet, 'integrationTestImplementation' configuration,
// and the 'integrationTest' Test task are now defined and registered by the
// root project's 'subprojects { ... }' block.
// So, you REMOVE the following sections from this file:
//
// REMOVE:
// /* -------------- integration-test source set -------------- */
// val integrationTest by sourceSets.creating {
//     // It's good practice to configure it directly
// }
// configurations {
//     val integrationTestImplementation by getting {
//         extendsFrom(configurations.testImplementation.get())
//     }
//     // If you need compileOnly or runtimeOnly for integrationTest, define them similarly
//     // val integrationTestCompileOnly by getting { ... }
//     // val integrationTestRuntimeOnly by getting { ... }
// }
//
// REMOVE:
// tasks.register<Test>("integrationTest") {
//     description = "Spin Testcontainers Pulsar and run E2E tests."
//     group = "verification"
//     testClassesDirs = integrationTest.output.classesDirs
//     classpath = integrationTest.runtimeClasspath
//     shouldRunAfter(tasks.test)
//     useJUnitPlatform() // Add this if your integration tests also use JUnit Platform
// }
//
// REMOVE: (This was incorrectly placed here, it belongs in root or not at all if root handles it)
// // In root build.gradle.kts -> subprojects { ... }
// tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
//     compilerOptions {
//         jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_23) // Or JVM_21, JVM_22
//     }
// }


dependencies {
    // Use the BOM from the version catalog
    implementation(platform(libs.pulsar.bom))
    // Pick ONE of these for pulsar-functions-api:
    implementation("org.apache.pulsar:pulsar-functions-api") // If BOM manages it or you want default version from BOM
    // implementation("org.apache.pulsar:pulsar-functions-api:${libs.versions.pulsar.get()}") // If you need a specific version NOT in BOM

    implementation(project(":common"))
    implementation(libs.jackson.module.kotlin) // This brings in com.fasterxml.jackson.module.kotlin


    testImplementation(project(":test-kit"))
    testImplementation(libs.jackson.module.kotlin) // Fine if test-kit doesn't already provide it via 'api'

    // If this module HAS integration tests, add their dependencies here:
    // integrationTestImplementation(project(":test-kit"))
    // integrationTestImplementation(libs.testcontainers.pulsar) // Example
    // integrationTestImplementation(libs.pulsar.functions.local.runner) // Example
}

/* -------------- fat-JAR --------------- */
tasks.shadowJar {
    archiveClassifier.set("")                          // e.g., translators.jar
    // !!! IMPORTANT: Update this to the correct Main-Class for your translator function !!!
    manifest { attributes["Main-Class"] = "com.acme.pipeline.functions.YourTranslatorFunction" }
}

tasks.register<Zip>("makeNar") {
    dependsOn(tasks.shadowJar)
    archiveFileName.set("${project.name}-${project.version}.nar")
    from(zipTree(tasks.shadowJar.get().archiveFile)) {
        // Optional: exclude unnecessary files from the NAR if shadowJar includes too much
        // exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
}

/* -------------- container image -------------- */
jib {
    from.image = "eclipse-temurin:21-jre" // Ensure this matches your Kotlin JVM target (e.g., JVM_21)
    to.image   = "ghcr.io/acme/${project.name}:${project.version}" // This seems appropriate for 'translators'
    container.entrypoint = listOf()                    // Function Mesh handles cmd
    // Consider setting container.appRoot or other jib configurations
}