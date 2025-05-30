// functions/splitter/build.gradle.kts

plugins {
    // id("org.jetbrains.kotlin.jvm") // Applied by root
    id("com.github.johnrengelman.shadow")
    id("com.google.cloud.tools.jib")
}

jacoco {
    toolVersion = libs.versions.jacoco.get() // Should resolve to "0.8.12"
}

var catalog = libs

subprojects {
  apply(plugin = "com.github.johnrengelman.shadow")

  dependencies {
    implementation(platform(catalog.pulsar.bom))      // centralized versions
    implementation(catalog.pulsar.functions.api)
    implementation(project(":libs"))
    implementation(catalog.jackson.module.kotlin)

    testImplementation(project(":libs"))
    testImplementation(project(":test-kit"))
    testImplementation(catalog.testcontainers.pulsar)
    testImplementation(catalog.pulsar.client.admin.original)
  }

  tasks.register<Zip>("makeNar") {
    dependsOn(tasks.shadowJar)
    // only unzip shadowJar if you need the plain jar too:
    dependsOn(tasks.named("jar"))

    archiveFileName.set("${project.name}.nar")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    isZip64 = true

    from(zipTree(tasks.shadowJar.get().archiveFile)) {
      // if needed: exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
  }

  tasks.named("assemble") {
    dependsOn(tasks.named("makeNar"))
  }
}


jib {
    from.image = "eclipse-temurin:17-jre"
    to.image   = "ghcr.io/acme/${project.name}:${project.version}"
    container.entrypoint = listOf()
}
