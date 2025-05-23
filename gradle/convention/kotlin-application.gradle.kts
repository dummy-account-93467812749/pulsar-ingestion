import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar // Optional but good for clarity

plugins {
    kotlin("jvm")
    alias(libs.plugins.shadow) // Corresponds to [plugins.shadow] in libs.versions.toml
}

// Kotlin compiler options (similar to kotlin-library but explicit-api might be omitted for apps)
tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + listOf(
            "-Xjsr305=strict",
            "-Xwarnings-as-errors"
            // -Xexplicit-api=strict is often omitted for final applications
        )
    }
}

dependencies {
    implementation(libs.kotlin.stdlib) // Assumes libs.kotlin.stdlib is in libs.versions.toml
    implementation(libs.kotlin.reflect) // Assumes libs.kotlin.reflect is in libs.versions.toml
}

// Shadow plugin configuration
tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("all")
    // Output to a common directory at the root of the project's build directory
    destinationDirectory.set(project.rootProject.layout.buildDirectory.dir("deployable_jars"))
    // mergeServiceFiles() // Uncomment if service file merging is needed
}

// Ensure shadowJar runs as part of the standard assemble task (which is part of build)
tasks.named("assemble") {
    dependsOn(tasks.named("shadowJar"))
}
