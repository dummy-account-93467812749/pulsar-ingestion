// connectors/grpc/build.gradle.kts

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("java-library")
    id("com.google.cloud.tools.jib")
    alias(libs.plugins.shadow) // Apply shadow plugin
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

// Configure the shadowJar task for lean NAR generation
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set(project.name) // Assumes project.name is "grpc" or similar
    archiveClassifier.set("")
    archiveExtension.set("nar")
    archiveVersion.set("") // Ensure version-less NAR filename, e.g., "grpc.nar"

    dependencies {
        // Exclude Pulsar APIs (Pulsar IO Core, etc.)
        exclude(dependency("org.apache.pulsar:pulsar-io-core"))
        exclude(dependency("org.apache.pulsar:pulsar-client-api"))
        exclude(dependency("org.apache.pulsar:pulsar-common"))
        exclude(dependency("org.apache.pulsar:.*")) // Broader Pulsar exclusion

        // Exclude logging frameworks
        exclude(dependency("org.slf4j:.*"))
        exclude(dependency("ch.qos.logback:.*"))
        exclude(dependency("org.apache.logging.log4j:.*"))

        // Exclude test dependencies
        exclude(dependency(":test-kit")) // Project's test kit
        exclude(dependency("org.junit.jupiter:.*"))
        exclude(dependency("org.testcontainers:.*"))
        // Add other common test libraries that might be pulled in transitively
        exclude(dependency("org.mockito:.*"))
        exclude(dependency("net.bytebuddy:.*")) // Often a mockito dependency

        // Include necessary gRPC and other runtime dependencies for the connector
        // These are examples; actual dependencies from libs.versions.toml should be used if known.
        // Assuming standard gRPC dependencies:
        include(dependency("io.grpc:grpc-api"))
        include(dependency("io.grpc:grpc-core")) // May not be needed if netty-shaded includes it
        include(dependency("io.grpc:grpc-netty-shaded"))
        include(dependency("io.grpc:grpc-protobuf"))
        include(dependency("io.grpc:grpc-stub"))
        include(dependency("com.google.protobuf:protobuf-java"))
        include(dependency("com.google.protobuf:protobuf-java-util"))
        include(dependency("com.google.guava:guava")) // Often a core gRPC dependency
        // Add other specific dependencies if the gRPC connector uses them, e.g., specific auth libs.
    }
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