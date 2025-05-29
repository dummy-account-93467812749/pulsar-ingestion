// connectors/grpc/build.gradle.kts

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("java-library")
    id("com.google.cloud.tools.jib")
}

// group = "com.acme.pulsar.connectors" // Optional: if not set by parent
// version = "0.1.0-SNAPSHOT" // Optional: if not set by parent

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

dependencies {
    implementation(platform(libs.pulsar.bom))
    implementation(libs.pulsar.io.core) // Corrected accessor

    // connector-specific libs
    // e.g., implementation(libs.grpc.netty.shaded)
    //      implementation(libs.grpc.protobuf)
    //      implementation(libs.grpc.stub)

    testImplementation(project(":test-kit"))
}

/* -------------- container image -------------- */
jib {
    from.image = "eclipse-temurin:17-jre"
    // project.name should resolve to "grpc" or "grpc-connector" depending on actual project name in settings.gradle.kts
    to.image   = "ghcr.io/acme/${project.name}:${project.version}" 
    container.entrypoint = listOf() // Pulsar IO runtime will call the connector
    // Example: If your connector JAR needs to be in a specific directory for Pulsar IO.
    // Jib usually packages the JAR appropriately, but if customization is needed:
    // container.appRoot = "/pulsar/connectors" // Or other path as required by Pulsar's classloading for connectors
    // Or more explicitly:
    // extraDirectories.paths {
    //   path {
    //     from = layout.buildDirectory.dir("libs")
    //     into = "/pulsar/connectors" // Target directory in the image for connector JARs
    //     includes += ["*.jar"]
    //   }
    // }
}