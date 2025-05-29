// settings.gradle.kts

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
  // prohibit individual projects from declaring their own repositories
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

  // define the only repositories your build can use:
  repositories {
    // official Maven Central
    mavenCentral()

    // Pulsar releases (Apache’s repository)
    maven {
      url = uri("https://repository.apache.org/content/repositories/releases/")
      // optionally, only look for metadata in POMs:
      metadataSources {
        mavenPom()
        artifact()
      }
    }
  }
}

rootProject.name = "pulsar-ingestion"

include(
    "common",
    "test-kit",
    "connectors:grpc",
    "functions:splitter",
    "functions:translators",
    "functions:translators:inventory-update-translator",
    "functions:translators:order-record-translator",
    "functions:translators:payment-notice-translator",
    "functions:translators:shipment-status-translator",
    "functions:translators:user-profile-translator",
    "functions:translators:translators-integration"
)