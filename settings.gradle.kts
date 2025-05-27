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
    "connectors:azure-eventhub-source",
    "connectors:grpc-source",
    "connectors:http-source",
    "connectors:kafka-source",
    "connectors:kinesis-source",
    "connectors:pulsar-source",
    "connectors:rabbitmq-source",
    "functions:splitter",
    "functions:translators"
)