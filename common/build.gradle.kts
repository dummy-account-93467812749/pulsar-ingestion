// common/build.gradle.kts
plugins {
    id("org.jetbrains.kotlin.jvm") // Version is inherited from root project
}

dependencies {
    // Use platform BOM from the version catalog
    implementation(platform(libs.pulsar.bom))

    // Use jackson from the version catalog
    api(libs.jackson.databind)

    // kotlin("stdlib-jdk8") automatically uses the version from the Kotlin plugin.
    // This is generally fine. If you had added kotlin-stdlib-jdk8 to your catalog,
    // you could use implementation(libs.kotlin.stdlib.jdk8)
    implementation(kotlin("stdlib-jdk8"))
}