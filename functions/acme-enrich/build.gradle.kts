// Apply conventions
apply(from = "${rootDir}/gradle/convention/kotlin-application.gradle.kts")
apply(from = "${rootDir}/gradle/convention/detekt.gradle.kts")
apply(from = "${rootDir}/gradle/convention/ktlint.gradle.kts")

// Specific dependencies for this application module
dependencies {
    implementation(project(":functions:common-models"))
    implementation(libs.pulsar.client) // Ensure this is in libs.versions.toml

    // Logging
    implementation(libs.slf4j.api) // Ensure this is in libs.versions.toml
    runtimeOnly(libs.logback.classic) // Ensure this is in libs.versions.toml
}
