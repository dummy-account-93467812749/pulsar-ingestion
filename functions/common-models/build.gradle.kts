plugins {
    kotlin("jvm")
}

dependencies {
    implementation(libs.kotlin.stdlib)
    // api(libs.pulsar.client.api) // Example: if models needed Pulsar types
                                 // No 'pulsar.client.api' alias in libs.versions.toml
}
