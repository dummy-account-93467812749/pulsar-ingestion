package org.apache.pulsar.io.pulsar.source

import org.apache.pulsar.client.api.SubscriptionInitialPosition
import org.apache.pulsar.client.api.SubscriptionType
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNotNull
import org.testng.Assert.assertNull
import org.testng.Assert.expectThrows
import org.testng.annotations.DataProvider
import org.testng.annotations.Test
import java.io.File

class PulsarSourceConfigTest {

    private fun createValidConfigMap(): MutableMap<String, Any> {
        return mutableMapOf(
            "serviceUrl" to "pulsar://localhost:6650",
            "topicNames" to listOf("persistent://public/default/test-input"),
            "subscriptionName" to "test-sub",
            "subscriptionType" to "Shared", // String representation
            "subscriptionInitialPosition" to "Earliest", // String representation
        )
    }

    @Test
    fun testLoadConfigFromMapMinimal() {
        val configMap = createValidConfigMap()
        val config = PulsarSourceConfig.load(configMap)

        assertEquals(config.serviceUrl, "pulsar://localhost:6650")
        assertNotNull(config.topicNames)
        assertEquals(config.topicNames!!.size, 1)
        assertEquals(config.topicNames!![0], "persistent://public/default/test-input")
        assertNull(config.topicsPattern)
        assertEquals(config.subscriptionName, "test-sub")
        assertEquals(config.subscriptionType, SubscriptionType.Shared)
        assertEquals(config.subscriptionInitialPosition, SubscriptionInitialPosition.Earliest)
        // Check defaults for other fields
        assertEquals(config.receiverQueueSize, 1000)
        assertEquals(config.ackTimeoutMillis, 30000L)
    }

    @Test
    fun testLoadConfigFromFile() {
        val yaml = """
            serviceUrl: "pulsar+ssl://other.host:6651"
            webServiceUrl: "https://other.host:8443"
            topicsPattern: "persistent://public/default/pattern-.*"
            subscriptionName: "file-sub"
            subscriptionType: "Failover"
            subscriptionInitialPosition: "Latest"
            receiverQueueSize: 500
            ackTimeoutMillis: 60000
            negativeAckRedeliveryDelayMicros: 120000000
            deadLetterPolicy:
              maxRedeliverCount: 5
              deadLetterTopic: "persistent://public/default/file-dlq"
              initialSubscriptionName: "file-dlq-sub"
            retryEnable: true
            authPluginClassName: "org.apache.pulsar.client.impl.auth.AuthenticationToken"
            authParams: "token:file-token"
            tlsTrustCertsFilePath: "/path/to/file_ca.crt"
            tlsAllowInsecureConnection: true
            tlsHostnameVerificationEnable: true
            consumerProperties:
              "some.consumer.prop": "value1"
            forwardKey: false
            forwardProperties: false
        """.trimIndent()
        val tempFile = File.createTempFile("pulsar-source-config", ".yaml")
        tempFile.writeText(yaml)
        tempFile.deleteOnExit()

        val config = PulsarSourceConfig.load(tempFile.absolutePath)

        assertEquals(config.serviceUrl, "pulsar+ssl://other.host:6651")
        assertEquals(config.webServiceUrl, "https://other.host:8443")
        assertNull(config.topicNames)
        assertEquals(config.topicsPattern, "persistent://public/default/pattern-.*")
        assertEquals(config.subscriptionName, "file-sub")
        assertEquals(config.subscriptionType, SubscriptionType.Failover)
        assertEquals(config.subscriptionInitialPosition, SubscriptionInitialPosition.Latest)
        assertEquals(config.receiverQueueSize, 500)
        assertEquals(config.ackTimeoutMillis, 60000L)
        assertEquals(config.negativeAckRedeliveryDelayMicros, 120000000L)
        assertNotNull(config.deadLetterPolicy)
        assertEquals(config.deadLetterPolicy!!.maxRedeliverCount, 5)
        assertEquals(config.deadLetterPolicy!!.deadLetterTopic, "persistent://public/default/file-dlq")
        assertEquals(config.deadLetterPolicy!!.initialSubscriptionName, "file-dlq-sub")
        assertEquals(config.retryEnable, true)
        assertEquals(config.authPluginClassName, "org.apache.pulsar.client.impl.auth.AuthenticationToken")
        assertEquals(config.authParams, "token:file-token")
        assertEquals(config.tlsTrustCertsFilePath, "/path/to/file_ca.crt")
        assertEquals(config.tlsAllowInsecureConnection, true)
        assertEquals(config.tlsHostnameVerificationEnable, true)
        assertNotNull(config.consumerProperties)
        assertEquals(config.consumerProperties!!["some.consumer.prop"], "value1")
        assertEquals(config.forwardKey, false)
        assertEquals(config.forwardProperties, false)
    }

    @DataProvider(name = "invalidTopicConfigs")
    fun invalidTopicConfigs(): Array<Array<Any>> {
        return arrayOf(
            arrayOf(
                createValidConfigMap().apply {
                    remove("topicNames")
                    remove("topicsPattern")
                },
            ), // Neither provided
            arrayOf(createValidConfigMap().apply { this["topicNames"] = emptyList<String>() }), // Empty topicNames list
            arrayOf(createValidConfigMap().apply { this["topicsPattern"] = "  " }), // Blank topicsPattern
        )
    }

    @Test(dataProvider = "invalidTopicConfigs")
    fun testValidationFails_InvalidTopicConfig(invalidMap: Map<String, Any>) {
        expectThrows(IllegalArgumentException::class.java) {
            PulsarSourceConfig.load(invalidMap)
        }
    }

    @Test
    fun testValidationFails_BothTopicNamesAndPattern() {
        val configMap = createValidConfigMap().apply {
            this["topicsPattern"] = "persistent://public/default/some-pattern"
        } // topicNames is already in createValidConfigMap
        expectThrows(IllegalArgumentException::class.java) {
            PulsarSourceConfig.load(configMap)
        }
    }

    @Test
    fun testValidationFails_MissingSubscriptionName() {
        val configMap = createValidConfigMap()
        configMap.remove("subscriptionName")
        expectThrows(IllegalArgumentException::class.java) {
            PulsarSourceConfig.load(configMap)
        }
    }

    @Test
    fun testValidationFails_BlankSubscriptionName() {
        val configMap = createValidConfigMap().apply { this["subscriptionName"] = " " }
        expectThrows(IllegalArgumentException::class.java) {
            PulsarSourceConfig.load(configMap)
        }
    }

    @Test
    fun testValidationFails_InvalidDeadLetterPolicy_MissingTopic() {
        val configMap = createValidConfigMap().apply {
            this["deadLetterPolicy"] = mapOf("maxRedeliverCount" to 5) // DLQ topic missing
        }
        expectThrows(IllegalArgumentException::class.java) {
            PulsarSourceConfig.load(configMap)
        }
    }

    @Test
    fun testValidationFails_InvalidDeadLetterPolicy_NegativeRedeliverCount() {
        val configMap = createValidConfigMap().apply {
            this["deadLetterPolicy"] = mapOf("maxRedeliverCount" to -1, "deadLetterTopic" to "dlq")
        }
        expectThrows(IllegalArgumentException::class.java) {
            PulsarSourceConfig.load(configMap)
        }
    }

    @Test
    fun testEnumConversionFromMap() {
        val configMap = mapOf(
            "serviceUrl" to "pulsar://localhost:6650",
            "topicNames" to listOf("persistent://public/default/test-input"),
            "subscriptionName" to "test-sub",
            "subscriptionType" to "Key_Shared", // String representation
            "subscriptionInitialPosition" to "Earliest", // String representation
        )
        val config = PulsarSourceConfig.load(configMap)
        assertEquals(config.subscriptionType, SubscriptionType.Key_Shared)
        assertEquals(config.subscriptionInitialPosition, SubscriptionInitialPosition.Earliest)
    }

    @Test
    fun testInvalidEnumConversionFromMap_SubscriptionType() {
        val configMap = mapOf(
            "serviceUrl" to "pulsar://localhost:6650",
            "topicNames" to listOf("persistent://public/default/test-input"),
            "subscriptionName" to "test-sub",
            "subscriptionType" to "InvalidType",
        )
        expectThrows(IOException::class.java) {
            PulsarSourceConfig.load(configMap)
        }
    }

    @Test
    fun testInvalidEnumConversionFromMap_SubscriptionInitialPosition() {
        val configMap = mapOf(
            "serviceUrl" to "pulsar://localhost:6650",
            "topicNames" to listOf("persistent://public/default/test-input"),
            "subscriptionName" to "test-sub",
            "subscriptionInitialPosition" to "InvalidPosition",
        )
        expectThrows(IOException::class.java) {
            PulsarSourceConfig.load(configMap)
        }
    }
}
