package com.example.pulsar.filterer

import com.example.pulsar.common.CommonMessageFormat // Adjust if your CMF classes are in a different sub-package
// import com.example.pulsar.common.CommonMeta // Not strictly needed if only using meta?.tenantId
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.apache.pulsar.client.api.Schema
import org.apache.pulsar.functions.api.Context
import org.apache.pulsar.functions.api.Function
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets

class Filterer : Function<String, Unit> {
    // Companion object for logger and ObjectMapper instance for efficiency
    companion object {
        private val log = LoggerFactory.getLogger(Filterer::class.java)
        private val objectMapper = jacksonObjectMapper()
        private val cmfTypeRef = object : TypeReference<CommonMessageFormat<JsonNode>>() {}
    }

    override fun process(input: String, context: Context) {
        val messageId = context.currentRecord?.messageId?.toString() ?: "unknown"
        try {
            log.info("Filterer received message (ID: {}): {}", messageId, input)

            val cmfMessage: CommonMessageFormat<JsonNode> = objectMapper.readValue(input, cmfTypeRef)

            val tenantId = cmfMessage.meta?.tenantId

            if (tenantId.isNullOrBlank()) {
                log.warn("tenantId is missing or blank in message (ID: {}). Skipping routing.", messageId)
                return
            }

            val outputTopic = "persistent://${tenantId}/integration/telemetry"
            log.info("Routing message (ID: {}) to tenant topic: {}", messageId, outputTopic)

            context.newOutputMessage(outputTopic, Schema.STRING)
                .value(input)
                .sendAsync()
                .exceptionally { ex ->
                    log.error("Filterer failed to send message (ID: {}) to topic {}: {}", messageId, outputTopic, ex.message, ex)
                    null
                }

        } catch (e: Exception) {
            log.error("Filterer error processing message (ID: {}): {}. Input: {}", messageId, e.message, input, e)
        }
    }
}
