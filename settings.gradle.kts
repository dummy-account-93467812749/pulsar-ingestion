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
    "libs",
    "test-kit",
    // Connectors
    "pulsar-components:connectors:azure-eventhub",
    "pulsar-components:connectors:grpc",
    "pulsar-components:connectors:http",
    "pulsar-components:connectors:kafka",
    "pulsar-components:connectors:kinesis",
    "pulsar-components:connectors:rabbitmq",
    // Filterer (formerly splitter)
    "pulsar-components:filterer",
    // CMF (Custom Message Format translators and integration)
    "pulsar-components:cmf:vehicle-telemetry-translators",
    "pulsar-components:cmf:translators-integration"
)