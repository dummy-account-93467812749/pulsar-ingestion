plugins {
    kotlin("jvm") // Ensure Kotlin plugin is applied
}

dependencies {
    // Using 'api' so modules depending on 'common' also get this transitively
    api("com.fasterxml.jackson.core:jackson-databind:2.15.3") // Use a recent, stable version
    implementation(kotlin("stdlib-jdk8")) // Kotlin standard library
}
