// connectors/grpc-source/build.gradle.kts

plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    implementation(platform(libs.pulsar.bom))
    implementation(libs.pulsar.io.core)  // Corrected accessor

    // connector-specific libs
    // e.g., implementation(libs.grpc.netty.shaded)
    //      implementation(libs.grpc.protobuf)
    //      implementation(libs.grpc.stub)

    testImplementation(project(":test-kit"))
}