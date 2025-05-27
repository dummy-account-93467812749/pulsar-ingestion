package org.apache.pulsar.io.azureeventhub.source

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import java.io.IOException
import java.io.Serializable

data class AzureEventHubSourceConfig(
    // Event Hubs Connection Details
    var fullyQualifiedNamespace: String? = null,
    var eventHubName: String? = null,
    var consumerGroup: String = "\$Default", // Default consumer group in Event Hubs

    // Authentication: Connection String has precedence
    var connectionString: String? = null,

    // Credential-based authentication (used if connectionString is not provided)
    var credential: AzureCredentialConfig? = null,

    // Checkpoint Store: Azure Blob Storage
    var checkpointStore: CheckpointStoreConfig? = null,

    // Event Processor Client settings
    var prefetchCount: Int? = null, // Azure SDK default is 300, null means use SDK default
    var initialPartitionEventPosition: Map<String, String>? = null, // PartitionID to position string
    var maxBatchSize: Int? = null, // Azure SDK default is 10
    var maxWaitTimeInSeconds: Int? = null, // Azure SDK default is 1

    // Pulsar specific settings
    var receiveQueueSize: Int = 1000,
    var pulsarFormat: String = "BYTES" // Future: STRING, JSON

) : Serializable {

    // Nested class for credential configuration
    data class AzureCredentialConfig(
        var tenantId: String? = null,
        var clientId: String? = null,
        var clientSecret: String? = null,
        var authorityHost: String? = null // e.g., "https://login.microsoftonline.com/"
    ) : Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    // Nested class for checkpoint store configuration
    data class CheckpointStoreConfig(
        var blobContainerUrl: String? = null, // Option A: URL + DefaultAzureCredential (or parent credential)

        // Option B: Storage account details + specific storage credential (if different from Event Hubs)
        var storageAccountName: String? = null,
        var storageContainerName: String? = null,
        var storageConnectionString: String? = null // For storage account access
        // Could also add fields for storage account access key or SAS token if needed
    ) : Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    // Validation logic
    fun validate() {
        require(!fullyQualifiedNamespace.isNullOrBlank()) { "fullyQualifiedNamespace must be set" }
        require(!eventHubName.isNullOrBlank()) { "eventHubName must be set" }
        require(consumerGroup.isNotBlank()) { "consumerGroup must be set" }

        // Authentication:
        // Either connectionString or some form of credential (even if empty for DefaultAzureCredential) must be implied.
        // If connectionString is null, then credential object might exist (for DefaultAzureCredential) or be configured.
        // DefaultAzureCredential can work without explicit config if environment is set.
        // For simplicity, we'll assume if connectionString is null, credential-based auth is intended.

        // Checkpoint store:
        require(checkpointStore != null) { "checkpointStore configuration must be provided" }
        checkpointStore?.let {
            val optionAProvided = !it.blobContainerUrl.isNullOrBlank()
            val optionBProvided = !it.storageAccountName.isNullOrBlank() && !it.storageContainerName.isNullOrBlank()

            require(optionAProvided || optionBProvided) {
                "For checkpointStore, either 'blobContainerUrl' (with appropriate top-level credential for access) " +
                        "OR 'storageAccountName' and 'storageContainerName' must be provided."
            }
            if (optionBProvided && it.storageConnectionString.isNullOrBlank()) {
                // If storage account name/container is given, but no specific storage connection string,
                // then the top-level credential (if any) will be used for blob access.
                // This is a valid scenario for DefaultAzureCredential with correct RBAC.
            }
        }
    }

    companion object {
        private const val serialVersionUID = 1L
        private val MAPPER = ObjectMapper(YAMLFactory()).registerKotlinModule()

        @JvmStatic
        @Throws(IOException::class)
        fun load(configFile: String): AzureEventHubSourceConfig {
            val config = MAPPER.readValue<AzureEventHubSourceConfig>(File(configFile))
            config.validate()
            return config
        }

        @JvmStatic
        @Throws(IOException::class)
        fun load(configMap: Map<String, Any>): AzureEventHubSourceConfig {
            val config = MAPPER.convertValue(configMap, AzureEventHubSourceConfig::class.java)
            config.validate()
            return config
        }
    }
}
