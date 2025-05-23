import io.gitlab.arturbosch.detekt.extensions.DetektExtension

plugins {
    alias(libs.plugins.detekt) // From [plugins] in libs.versions.toml
}

// Configure the Detekt extension
configure<DetektExtension> {
    // Point to the project-specific Detekt configuration file.
    // Assumes config/detekt/detekt.yml exists at the root of the project.
    config.setFrom(project.files("${project.rootDir}/config/detekt/detekt.yml"))

    // Use default Detekt config as a baseline.
    buildUponDefaultConfig = true

    // Disable auto-correction (suitable for CI environments).
    autoCorrect = false

    // Ensure that Detekt findings will fail the build.
    ignoreFailures = false // This is a direct property of DetektExtension

    // Configure reports.
    reports {
        xml {
            required.set(true) // Enabled for CI or other tools
            // outputLocation.set(file("build/reports/detekt/detekt.xml")) // Default is fine
        }
        html {
            required.set(true) // Enabled for human review
            // outputLocation.set(file("build/reports/detekt/detekt.html")) // Default is fine
        }
        txt {
            required.set(false) // Disabled
        }
        sarif {
            required.set(false) // Disabled (or true if needed for other integrations)
        }
    }

    // Source detection:
    // Explicitly set source directories for Detekt.
    source.setFrom(files(
        project.layout.projectDirectory.dir("src/main/kotlin"),
        project.layout.projectDirectory.dir("src/test/kotlin"),
        project.layout.projectDirectory.dir("src/integrationTest/kotlin")
    ))
}

// Integration with the 'check' lifecycle task:
// Detekt typically integrates with the 'check' task by default when the plugin is applied.
// If it doesn't, or to make it explicit, you could add:
// tasks.named("check") {
//     dependsOn(tasks.withType<io.gitlab.arturbosch.detekt.Detekt>())
// }
// For now, relying on the default behavior.
