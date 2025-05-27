package org.apache.pulsar.io.http

import com.fasterxml.jackson.databind.JsonNode // Added import
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import java.io.IOException
import java.io.Serializable
// import java.util.Map // No longer directly used in load signature for configMap

data class HttpSourceConfig(
    var url: String? = null,
    var method: String? = "GET",
    var headers: java.util.Map<String, String>? = null,
    var requestBody: String? = null,
    var pollingIntervalMs: Long? = 5000L,
    var connectTimeoutMs: Long? = 5000L,
    var readTimeoutMs: Long? = 10000L,
) : Serializable {

    companion object {
        private val serialVersionUID = 1L
        private val YAML_MAPPER = ObjectMapper(YAMLFactory()).registerKotlinModule() // For loading from file
        private val JSON_MAPPER = ObjectMapper().registerKotlinModule() // For converting from map

        @JvmStatic
        @Throws(IOException::class)
        fun load(configFile: String): HttpSourceConfig {
            return YAML_MAPPER.readValue(File(configFile))
        }

        @JvmStatic
        @Throws(IOException::class)
        fun load(configMap: Map<String, Any>): HttpSourceConfig {
            // Convert Map to JsonNode, then JsonNode to HttpSourceConfig
            val jsonNode: JsonNode = JSON_MAPPER.valueToTree(configMap)
            return JSON_MAPPER.treeToValue(jsonNode, HttpSourceConfig::class.java)
        }
    }
}
