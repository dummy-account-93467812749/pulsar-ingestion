package org.apache.pulsar.io.kafka.source

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import java.io.IOException
import java.io.Serializable
import java.util.Properties

data class KafkaSourceConfig(
    var bootstrapServers: String = "localhost:9092",
    var topic: String = "",
    var groupId: String = "pulsar-kafka-source-group",
    var keyDeserializer: String = "org.apache.kafka.common.serialization.StringDeserializer",
    var valueDeserializer: String = "org.apache.kafka.common.serialization.ByteArrayDeserializer",
    var autoOffsetReset: String = "earliest",
    var maxPollRecords: Int = 500,
    var pollIntervalMs: Long = 1000L,
    var securityProtocol: String? = null,
    var saslMechanism: String? = null,
    var saslJaasConfig: String? = null,
    var sslTruststoreLocation: String? = null,
    var sslTruststorePassword: String? = null,
    var sslKeystoreLocation: String? = null,
    var sslKeystorePassword: String? = null,
    var sslKeyPassword: String? = null,
    var additionalProperties: Map<String, String>? = null,
) : Serializable {

    fun toProperties(): Properties {
        val props = Properties()
        props["bootstrap.servers"] = bootstrapServers
        props["group.id"] = groupId
        props["key.deserializer"] = keyDeserializer
        props["value.deserializer"] = valueDeserializer
        props["auto.offset.reset"] = autoOffsetReset
        props["max.poll.records"] = maxPollRecords.toString()
        // No direct Kafka property for pollIntervalMs for the consumer itself,
        // this is used by the source's polling loop.

        securityProtocol?.let { props["security.protocol"] = it }
        saslMechanism?.let { props["sasl.mechanism"] = it }
        saslJaasConfig?.let { props["sasl.jaas.config"] = it }
        sslTruststoreLocation?.let { props["ssl.truststore.location"] = it }
        sslTruststorePassword?.let { props["ssl.truststore.password"] = it }
        sslKeystoreLocation?.let { props["ssl.keystore.location"] = it }
        sslKeystorePassword?.let { props["ssl.keystore.password"] = it }
        sslKeyPassword?.let { props["ssl.key.password"] = it }

        additionalProperties?.forEach { (key, value) ->
            props[key] = value
        }
        return props
    }

    companion object {
        private const val serialVersionUID = 1L
        private val MAPPER = ObjectMapper(YAMLFactory()).registerKotlinModule()

        @JvmStatic
        @Throws(IOException::class)
        fun load(configFile: String): KafkaSourceConfig {
            return MAPPER.readValue(File(configFile))
        }

        @JvmStatic
        @Throws(IOException::class)
        fun load(configMap: Map<String, Any>): KafkaSourceConfig {
            return MAPPER.convertValue(configMap, KafkaSourceConfig::class.java)
        }
    }
}
