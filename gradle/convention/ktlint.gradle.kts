import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    alias(libs.plugins.ktlint) // Refers to [plugins.ktlint] in libs.versions.toml
}

// Configure the Ktlint extension
configure<KtlintExtension> {
    // Set the Ktlint tool version.
    // For org.jlleitschuh.gradle.ktlint plugin version 11.6.1, Ktlint tool version 0.50.0 is used.
    version.set("0.50.0")

    // Enable verbose output.
    verbose.set(true)

    // Disable Android-specific formatting.
    android.set(false)

    // Output lint results to the console.
    outputToConsole.set(true)

    // Fail the build if Ktlint violations are found.
    ignoreFailures.set(false)

    // enableExperimentalRules.set(false) // Experimental rules can be enabled if desired.

    // Filters can be configured here if specific files/directories need to be included/excluded.
    // Example:
    // filter {
    //     exclude("**/generated-sources/**")
    //     include("**/kotlin/**")
    // }
}

// The Ktlint plugin, by default, adds the 'ktlintCheck' task to the 'check' lifecycle task.
// This means running './gradlew check' will also run Ktlint checks.
// If this behavior is not default or needs to be made explicit, you could use:
// tasks.named("check") {
//     dependsOn(tasks.named("ktlintCheck"))
// }

// The 'ktlintFormat' task can be used to automatically format code.
// It's often useful to run this manually or as part of a pre-commit hook,
// rather than automatically during every build in CI (where 'ktlintCheck' is preferred).
// Example: './gradlew ktlintFormat'
//
// To add ktlintFormat to the build lifecycle (e.g., before compilation), you might do:
// tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
//     dependsOn(tasks.named("ktlintFormat"))
// }
// However, this is often not done to keep CI builds faster (check-only).
