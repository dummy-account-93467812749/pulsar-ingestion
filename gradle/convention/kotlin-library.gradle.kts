import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    // No id("java-library") for now, as the example suggests it's optional
    // and the core request is for Kotlin library conventions.
}

// Configure Kotlin compilation tasks
tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + listOf(
            "-Xjsr305=strict",
            "-Xexplicit-api=strict",
            "-Xwarnings-as-errors"
            // Consider adding other args like "-opt-in=kotlin.RequiresOptIn" if needed later
        )
    }
}

// Common dependencies for Kotlin libraries
dependencies {
    implementation(libs.kotlin.stdlib) // Assumes libs.kotlin.stdlib is in libs.versions.toml
    implementation(libs.kotlin.reflect) // Assumes libs.kotlin.reflect is in libs.versions.toml
}

// If java-library plugin were used, could configure toolchain:
// java {
//     toolchain {
//         languageVersion.set(JavaLanguageVersion.of(17))
//     }
// }
