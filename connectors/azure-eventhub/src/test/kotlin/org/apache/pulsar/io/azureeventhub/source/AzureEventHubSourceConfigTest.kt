package org.apache.pulsar.io.azureeventhub.source

import org.testng.Assert.assertEquals
import org.testng.Assert.assertNotNull
import org.testng.Assert.assertNull
import org.testng.Assert.expectThrows
import org.testng.annotations.Test
import java.io.File

class AzureEventHubSourceConfigTest {

    private fun createValidConfigMap(): MutableMap<String, Any> {
        return mutableMapOf(
            "fullyQualifiedNamespace" to "test-namespace.servicebus.windows.net",
            "eventHubName" to "test-eventhub",
            "consumerGroup" to "test-consumer-group",
            "connectionString" to "Endpoint=sb://test.servicebus.windows.net/;SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=testKey=",
            "checkpointStore" to mapOf(
                "blobContainerUrl" to "https://teststorage.blob.core.windows.net/testcontainer",
                // Assuming credential-less access via SAS in URL or environment-based DefaultAzureCredential
            ),
        )
    }

    @Test
    fun testLoadConfigFromMapMinimal() {
        val configMap = createValidConfigMap()
        val config = AzureEventHubSourceConfig.load(configMap)

        assertEquals(config.fullyQualifiedNamespace, "test-namespace.servicebus.windows.net")
        assertEquals(config.eventHubName, "test-eventhub")
        assertEquals(config.consumerGroup, "test-consumer-group")
        assertEquals(config.connectionString, "Endpoint=sb://test.servicebus.windows.net/;SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=testKey=")
        assertNotNull(config.checkpointStore)
        assertEquals(config.checkpointStore!!.blobContainerUrl, "https://teststorage.blob.core.windows.net/testcontainer")
        // Check defaults
        assertEquals(config.receiveQueueSize, 1000)
        assertNull(config.prefetchCount)
    }

    @Test
    fun testLoadConfigFromFile() {
        val yaml = """
            fullyQualifiedNamespace: "file-namespace.servicebus.windows.net"
            eventHubName: "file-eventhub"
            consumerGroup: "${'$'}Default"
            credential:
              tenantId: "file-tenant"
              clientId: "file-client"
            checkpointStore:
              storageAccountName: "filestorage"
              storageContainerName: "filecontainer"
            prefetchCount: 150
            maxBatchSize: 5
            maxWaitTimeInSeconds: 2
            receiveQueueSize: 500
        """.trimIndent()
        val tempFile = File.createTempFile("azure-eh-config", ".yaml")
        tempFile.writeText(yaml)
        tempFile.deleteOnExit()

        val config = AzureEventHubSourceConfig.load(tempFile.absolutePath)

        assertEquals(config.fullyQualifiedNamespace, "file-namespace.servicebus.windows.net")
        assertEquals(config.eventHubName, "file-eventhub")
        assertEquals(config.consumerGroup, "\$Default")
        assertNotNull(config.credential)
        assertEquals(config.credential!!.tenantId, "file-tenant")
        assertEquals(config.credential!!.clientId, "file-client")
        assertNull(config.connectionString)
        assertNotNull(config.checkpointStore)
        assertEquals(config.checkpointStore!!.storageAccountName, "filestorage")
        assertEquals(config.checkpointStore!!.storageContainerName, "filecontainer")
        assertEquals(config.prefetchCount, 150)
        assertEquals(config.maxBatchSize, 5)
        assertEquals(config.maxWaitTimeInSeconds, 2)
        assertEquals(config.receiveQueueSize, 500)
    }

    @Test
    fun testValidationFails_MissingNamespace() {
        val configMap = createValidConfigMap()
        configMap.remove("fullyQualifiedNamespace")
        expectThrows(IllegalArgumentException::class.java) {
            AzureEventHubSourceConfig.load(configMap)
        }
    }

    @Test
    fun testValidationFails_MissingEventHubName() {
        val configMap = createValidConfigMap()
        configMap.remove("eventHubName")
        expectThrows(IllegalArgumentException::class.java) {
            AzureEventHubSourceConfig.load(configMap)
        }
    }

    @Test
    fun testValidationFails_MissingCheckpointStore() {
        val configMap = createValidConfigMap()
        configMap.remove("checkpointStore")
        expectThrows(IllegalArgumentException::class.java) {
            AzureEventHubSourceConfig.load(configMap)
        }
    }

    @Test
    fun testValidationFails_InvalidCheckpointStore_NoBlobContainerUrlOrAccountDetails() {
        val configMap = createValidConfigMap()
        configMap["checkpointStore"] = mapOf<String, String>() // Empty checkpoint store config
        expectThrows(IllegalArgumentException::class.java) {
            AzureEventHubSourceConfig.load(configMap)
        }
    }

    @Test
    fun testValidationFails_InvalidCheckpointStore_MissingContainerName() {
        val configMap = createValidConfigMap()
        configMap["checkpointStore"] = mapOf("storageAccountName" to "testAccount")
        expectThrows(IllegalArgumentException::class.java) {
            AzureEventHubSourceConfig.load(configMap)
        }
    }
}
