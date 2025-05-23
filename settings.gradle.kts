pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral() // For plugins not available on Gradle Plugin Portal
    }
}

rootProject.name = "pulsar-ingestion"
include("functions:common-models", "functions:acme-enrich", "tests:integration")