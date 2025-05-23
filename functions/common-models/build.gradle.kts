// Apply conventions
apply(from = "${rootDir}/gradle/convention/kotlin-library.gradle.kts")
apply(from = "${rootDir}/gradle/convention/detekt.gradle.kts")
apply(from = "${rootDir}/gradle/convention/ktlint.gradle.kts")

// Specific dependencies for this module, if any, beyond convention.
// For example, if it needs to expose Pulsar types in its API:
// dependencies {
//     api(libs.pulsar.client.api) // Ensure libs.pulsar.client.api is in libs.versions.toml
// }
