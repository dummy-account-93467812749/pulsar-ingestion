plugins {
kotlin("jvm") version "1.9.0" apply false
id("io.github.johnengelman.shadow") version "8.1.1" apply false
    // Detekt plugin is applied via convention plugin: gradle/convention/detekt.gradle.kts
    // and its version is managed in libs.versions.toml
}
allprojects {
group = "com.example.pulsar_ingestion"
version = "0.1.0-SNAPSHOT"
repositories {
mavenCentral()
}
}
subprojects {
apply(from = "${rootDir}/gradle/convention/detekt.gradle.kts")
apply(from = "${rootDir}/gradle/convention/ktlint.gradle.kts")

plugins.withType<org.jetbrains.kotlin.gradle.plugin.KotlinPluginWrapper> {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            jvmTarget = "17"
            freeCompilerArgs += listOf("-Xjsr305=strict")
        }
    }   
}