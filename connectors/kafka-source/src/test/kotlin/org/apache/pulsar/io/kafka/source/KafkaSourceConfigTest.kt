package org.apache.pulsar.io.kafka.source

import org.testng.Assert.assertEquals
import org.testng.Assert.assertNotNull
import org.testng.Assert.assertNull
import org.testng.annotations.Test
import java.io.File

class KafkaSourceConfigTest {

    @Test
    fun testLoadConfigFromFile() {
        val yaml = """
            bootstrapServers: "kafka.example.com:9092"
            topic: "test-topic"
            groupId: "test-group"
            keyDeserializer: "org.apache.kafka.common.serialization.IntegerDeserializer"
            valueDeserializer: "org.apache.kafka.common.serialization.StringDeserializer"
            autoOffsetReset: "latest"
            maxPollRecords: 100
            pollIntervalMs: 500
            securityProtocol: "SASL_SSL"
            saslMechanism: "PLAIN"
            saslJaasConfig: "jaas_config_string"
            sslTruststoreLocation: "/tmp/truststore.jks"
            sslTruststorePassword: "trustpassword"
            sslKeystoreLocation: "/tmp/keystore.jks"
            sslKeystorePassword: "keypassword"
            sslKeyPassword: "key_password_for_ssl"
            additionalProperties:
              "fetch.max.wait.ms": "1000"
              "enable.auto.commit": "false"
        """.trimIndent()
        val tempFile = File.createTempFile("kafka-source-config", ".yaml")
        tempFile.writeText(yaml)
        tempFile.deleteOnExit()

        val config = KafkaSourceConfig.load(tempFile.absolutePath)

        assertEquals(config.bootstrapServers, "kafka.example.com:9092")
        assertEquals(config.topic, "test-topic")
        assertEquals(config.groupId, "test-group")
        assertEquals(config.keyDeserializer, "org.apache.kafka.common.serialization.IntegerDeserializer")
        assertEquals(config.valueDeserializer, "org.apache.kafka.common.serialization.StringDeserializer")
        assertEquals(config.autoOffsetReset, "latest")
        assertEquals(config.maxPollRecords, 100)
        assertEquals(config.pollIntervalMs, 500L)
        assertEquals(config.securityProtocol, "SASL_SSL")
        assertEquals(config.saslMechanism, "PLAIN")
        assertEquals(config.saslJaasConfig, "jaas_config_string")
        assertEquals(config.sslTruststoreLocation, "/tmp/truststore.jks")
        assertEquals(config.sslTruststorePassword, "trustpassword")
        assertEquals(config.sslKeystoreLocation, "/tmp/keystore.jks")
        assertEquals(config.sslKeystorePassword, "keypassword")
        assertEquals(config.sslKeyPassword, "key_password_for_ssl")
        assertNotNull(config.additionalProperties)
        assertEquals(config.additionalProperties!!["fetch.max.wait.ms"], "1000")
        assertEquals(config.additionalProperties!!["enable.auto.commit"], "false")
    }

    @Test
    fun testLoadConfigFromMap() {
        val configMap = mapOf<String, Any>(
            "bootstrapServers" to "map.kafka.com:9093",
            "topic" to "map-topic",
            "groupId" to "map-group",
            "maxPollRecords" to 200,
        )

        val config = KafkaSourceConfig.load(configMap)

        assertEquals(config.bootstrapServers, "map.kafka.com:9093")
        assertEquals(config.topic, "map-topic")
        assertEquals(config.groupId, "map-group")
        assertEquals(config.maxPollRecords, 200)
        // Check default values for non-provided fields
        assertEquals(config.keyDeserializer, "org.apache.kafka.common.serialization.StringDeserializer")
        assertEquals(config.valueDeserializer, "org.apache.kafka.common.serialization.ByteArrayDeserializer")
        assertEquals(config.autoOffsetReset, "earliest")
        assertEquals(config.pollIntervalMs, 1000L)
        assertNull(config.securityProtocol)
    }

    @Test
    fun testToProperties() {
        val config = KafkaSourceConfig(
            bootstrapServers = "localhost:9092",
            topic = "props-topic",
            groupId = "props-group",
            keyDeserializer = "key.deser",
            valueDeserializer = "value.deser",
            autoOffsetReset = "none",
            maxPollRecords = 50,
            securityProtocol = "SSL",
            saslMechanism = "GSSAPI",
            additionalProperties = mapOf("custom.prop" to "custom.value"),
        )

        val props = config.toProperties()

        assertEquals(props["bootstrap.servers"], "localhost:9092")
        assertEquals(props["group.id"], "props-group")
        assertEquals(props["key.deserializer"], "key.deser")
        assertEquals(props["value.deserializer"], "value.deser")
        assertEquals(props["auto.offset.reset"], "none")
        assertEquals(props["max.poll.records"], "50")
        assertEquals(props["security.protocol"], "SSL")
        assertEquals(props["sasl.mechanism"], "GSSAPI")
        assertEquals(props["custom.prop"], "custom.value")
        assertNull(props["ssl.truststore.location"]) // Check that unset optional fields are not present
    }

    @Test
    fun testDefaultValues() {
        val configMap = mapOf<String, Any>(
            "topic" to "default-test-topic", // Only provide the mandatory topic
        )
        val config = KafkaSourceConfig.load(configMap)

        assertEquals(config.bootstrapServers, "localhost:9092")
        assertEquals(config.groupId, "pulsar-kafka-source-group")
        assertEquals(config.keyDeserializer, "org.apache.kafka.common.serialization.StringDeserializer")
        assertEquals(config.valueDeserializer, "org.apache.kafka.common.serialization.ByteArrayDeserializer")
        assertEquals(config.autoOffsetReset, "earliest")
        assertEquals(config.maxPollRecords, 500)
        assertEquals(config.pollIntervalMs, 1000L)
        assertNull(config.securityProtocol)
        assertNull(config.additionalProperties)
    }
}
