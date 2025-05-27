package org.apache.pulsar.io.pulsar.source

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.apache.pulsar.client.api.SubscriptionInitialPosition
import org.apache.pulsar.client.api.SubscriptionType
import java.io.File
import java.io.IOException
import java.io.Serializable

data class PulsarSourceConfig(
    // Pulsar Client Connection Configuration
    var serviceUrl: String = "pulsar://localhost:6650",
    var webServiceUrl: String? = null, // Optional

    // Pulsar Consumer Configuration
    var topicNames: List<String>? = null, // List of topics
    var topicsPattern: String? = null,    // Alternative to topicNames
    var subscriptionName: String = "pulsar-source-subscription",
    var subscriptionType: SubscriptionType = SubscriptionType.Shared,
    var subscriptionInitialPosition: SubscriptionInitialPosition = SubscriptionInitialPosition.Latest,
    var receiverQueueSize: Int = 1000,
    var ackTimeoutMillis: Long = 30000,
    var negativeAckRedeliveryDelayMicros: Long = 60000000, // 1 minute
    var deadLetterPolicy: DeadLetterPolicyConfig? = null,
    var retryEnable: Boolean = false,

    // Authentication (Optional)
    var authPluginClassName: String? = null,
    var authParams: String? = null, // Could be a JSON string or "key:value,key:value"

    // TLS Configuration (Optional)
    var tlsTrustCertsFilePath: String? = null,
    var tlsAllowInsecureConnection: Boolean = false,
    var tlsHostnameVerificationEnable: Boolean = false,
    var tlsKeyFilePath: String? = null,
    var tlsCertificateFilePath: String? = null,

    // Additional Pulsar consumer properties (as a map)
    var consumerProperties: Map<String, Any>? = null,

    // Pulsar Source specific
    var forwardKey: Boolean = true,
    var forwardProperties: Boolean = true,
    var forwardMessageId: Boolean = false,
    var forwardSequenceId: Boolean = false,
    var forwardPublishTime: Boolean = false

) : Serializable {

    data class DeadLetterPolicyConfig(
        var maxRedeliverCount: Int = 10,
        var deadLetterTopic: String? = null,
        var initialSubscriptionName: String? = null
    ) : Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
        fun validate() {
            require(maxRedeliverCount >= 0) { "maxRedeliverCount must be non-negative." }
            require(!deadLetterTopic.isNullOrBlank()) { "deadLetterTopic must be set if deadLetterPolicy is configured." }
        }
    }

    fun validate() {
        require(serviceUrl.isNotBlank()) { "serviceUrl must be set." }
        require(topicNames != null || topicsPattern != null) { "Either topicNames or topicsPattern must be configured." }
        require(topicNames == null || topicsPattern == null) { "Cannot configure both topicNames and topicsPattern." }
        topicNames?.let { require(it.isNotEmpty()) { "topicNames list cannot be empty." } }
        topicsPattern?.let { require(it.isNotBlank()) { "topicsPattern cannot be blank." } }
        require(subscriptionName.isNotBlank()) { "subscriptionName must be set." }
        deadLetterPolicy?.validate()
    }

    companion object {
        private const val serialVersionUID = 1L
        private val MAPPER = ObjectMapper(YAMLFactory()).registerKotlinModule()

        @JvmStatic
        @Throws(IOException::class)
        fun load(configFile: String): PulsarSourceConfig {
            val config = MAPPER.readValue<PulsarSourceConfig>(File(configFile))
            config.validate()
            return config
        }

        @JvmStatic
        @Throws(IOException::class)
        fun load(configMap: Map<String, Any>): PulsarSourceConfig {
            // Convert the subscriptionType and subscriptionInitialPosition from String to Enum
            // if they are provided as strings in the map (common from Pulsar function config)
            val mutableConfigMap = configMap.toMutableMap()

            configMap["subscriptionType"]?.let {
                if (it is String) {
                    try {
                        mutableConfigMap["subscriptionType"] = SubscriptionType.valueOf(it)
                    } catch (e: IllegalArgumentException) {
                        throw IOException("Invalid subscriptionType: $it. Valid values are: ${SubscriptionType.values().joinToString()}", e)
                    }
                }
            }
            configMap["subscriptionInitialPosition"]?.let {
                if (it is String) {
                    try {
                        mutableConfigMap["subscriptionInitialPosition"] = SubscriptionInitialPosition.valueOf(it)
                    } catch (e: IllegalArgumentException) {
                        throw IOException("Invalid subscriptionInitialPosition: $it. Valid values are: ${SubscriptionInitialPosition.values().joinToString()}", e)
                    }
                }
            }

            val config = MAPPER.convertValue(mutableConfigMap, PulsarSourceConfig::class.java)
            config.validate()
            return config
        }
    }
}
