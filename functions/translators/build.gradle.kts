// functions/translators/build.gradle.kts

plugins {
    // These are fine because their versions are managed by the root project's plugins block
    id("org.jetbrains.kotlin.jvm")
    id("com.github.johnrengelman.shadow")
    id("com.google.cloud.tools.jib")
}

dependencies {
    // Use the BOM from the version catalog
    implementation(platform(libs.pulsar.bom))
    implementation("org.apache.pulsar:pulsar-functions-api") // You could also add this to libs.versions.toml
    implementation(project(":common"))
    implementation(libs.jackson.module.kotlin)    // <-- this brings in com.fasterxml.jackson.module.kotlin
    implementation("org.apache.pulsar:pulsar-functions-api:${libs.versions.pulsar.get()}")


    testImplementation(project(":test-kit"))
    testImplementation(libs.jackson.module.kotlin)
}



// In root build.gradle.kts -> subprojects { ... }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_23) // Or JVM_21, JVM_22
    }
}

/* -------------- integration-test source set -------------- */
val integrationTest by sourceSets.creating {
    // It's good practice to configure it directly
}
configurations {
    val integrationTestImplementation by getting {
        extendsFrom(configurations.testImplementation.get())
    }
    // If you need compileOnly or runtimeOnly for integrationTest, define them similarly
    // val integrationTestCompileOnly by getting { ... }
    // val integrationTestRuntimeOnly by getting { ... }
}


tasks.register<Test>("integrationTest") {
    description = "Spin Testcontainers Pulsar and run E2E tests."
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    shouldRunAfter(tasks.test)
    useJUnitPlatform() // Add this if your integration tests also use JUnit Platform
}

/* -------------- fat-JAR --------------- */
tasks.shadowJar {
    archiveClassifier.set("")                          // e.g., translators.jar
    // Consider making the Main-Class configurable or derived if you have multiple functions per module
    manifest { attributes["Main-Class"] = "com.acme.pipeline.functions.SplitterFunction" }
}

tasks.register<Zip>("makeNar") {
    dependsOn(tasks.shadowJar)
    archiveFileName.set("${project.name}-${project.version}.nar") // This is fine
    from(zipTree(tasks.shadowJar.get().archiveFile)) {
        // Optional: exclude unnecessary files from the NAR if shadowJar includes too much
        // exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
}

/* -------------- container image -------------- */
jib {
    from.image = "eclipse-temurin:21-jre"
    to.image   = "ghcr.io/acme/${project.name}:${project.version}"
    container.entrypoint = listOf()                    // Function Mesh handles cmd
    // Consider setting container.appRoot or other jib configurations
}