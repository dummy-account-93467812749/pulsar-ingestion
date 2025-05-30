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
    "pulsar-components:connectors:azure-eventhub-source",
    "pulsar-components:connectors:grpc",
    "pulsar-components:connectors:grpc-source",
    "pulsar-components:connectors:http",
    "pulsar-components:connectors:http-source",
    "pulsar-components:connectors:kafka",
    "pulsar-components:connectors:kafka-source",
    "pulsar-components:connectors:kinesis",
    "pulsar-components:connectors:kinesis-source",
    "pulsar-components:connectors:pulsar-source",
    "pulsar-components:connectors:rabbitmq",
    "pulsar-components:connectors:rabbitmq-source",
    // Filterer (formerly splitter)
    "pulsar-components:filterer",
    // CMF (Custom Message Format translators and integration)
    "pulsar-components:cmf:inventory-update-translator",
    "pulsar-components:cmf:order-record-translator",
    "pulsar-components:cmf:payment-notice-translator",
    "pulsar-components:cmf:shipment-status-translator",
    "pulsar-components:cmf:user-profile-translator",
    "pulsar-components:cmf:translators-integration"
)