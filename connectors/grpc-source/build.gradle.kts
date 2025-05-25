plugins {
    id("org.jetbrains.kotlin.jvm") version "1.9.20"
    id("com.google.protobuf") version "0.9.4"
}

dependencies {
    implementation(project(":common"))
    implementation("org.apache.pulsar:pulsar-client-original:3.1.0")
    implementation("io.grpc:grpc-netty-shaded:1.58.0")
    implementation("io.grpc:grpc-protobuf:1.58.0")
    implementation("io.grpc:grpc-stub:1.58.0")
}

// Assuming protos are in src/main/proto
// Configure protobuf plugin if you add it
sourceSets {
    main {
        java {
            srcDirs("build/generated/source/proto/main/grpc")
            srcDirs("build/generated/source/proto/main/java")
        }
    }
}

protobuf {
  protoc {
    artifact = "com.google.protobuf:protoc:3.24.0"
  }
  plugins {
    create("grpc") {
      artifact = "io.grpc:protoc-gen-grpc-java:1.58.0"
    }
  }
  generateProtoTasks {
    all().forEach { task ->
      task.plugins.findByName("grpc")?.let { }
    }
  }
}
