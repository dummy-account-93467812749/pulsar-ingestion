package com.example.pulsar.functions.routing

import com.example.pulsar.common.CommonEvent
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.apache.pulsar.functions.api.Context
import org.apache.pulsar.functions.api.Function
import org.slf4j.Logger

/**
 * A Pulsar Function that routes incoming CommonEvent messages to different Pulsar topics
 * based on the eventType field of the CommonEvent.
 */
class EventTypeSplitter : Function<String, Void> {

    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    private lateinit var log: Logger

    override fun process(input: String?, context: Context?): Void? {
        // Ensure context and logger are available
        if (context == null) {
            // This case should ideally not happen if deployed in a Pulsar environment
            println("Error: Context is null. Cannot initialize logger or process message.")
            return null
        }
        log = context.logger

        // Handle null input
        if (input == null) {
            log.warn("Received null input. Skipping.")
            return null
        }

        log.debug("Received event string: {}", input)

        val commonEvent: CommonEvent
        try {
            commonEvent = objectMapper.readValue(input, CommonEvent::class.java)
        } catch (e: Exception) {
            log.error("Failed to deserialize input JSON to CommonEvent: {}. Error: {}", input, e.message, e)
            return null
        }

        log.info("Successfully deserialized eventId: {}, eventType: {}", commonEvent.eventId, commonEvent.eventType)

        // Sanitize eventType for use in topic names: lowercase and replace non-alphanumeric with hyphens
        val sanitizedEventType = commonEvent.eventType.lowercase().replace(Regex("[^a-z0-9-]"), "-")

        // Construct target topic name
        // Assuming a default tenant 'public' and namespace 'default' for routed topics.
        // This could be made configurable via userConfig if needed.
        val targetTopic = "persistent://public/default/fn-split-$sanitizedEventType"

        try {
            // Send the original input string to the target topic
            context.newOutputMessage(targetTopic, input.toByteArray()) // Send as byte array
                .sendAsync()
            // Note: The original plan mentioned .value(input) which is for schema-based messages.
            // For arbitrary JSON strings passed as String/byte[], just pass the payload directly.

            log.info("Routed eventId: {} (type: {}) to topic: {}", commonEvent.eventId, commonEvent.eventType, targetTopic)
        } catch (e: Exception) {
            log.error(
                "Failed to send message for eventId: {} (type: {}) to topic: {}. Error: {}",
                commonEvent.eventId,
                commonEvent.eventType,
                targetTopic,
                e.message,
                e,
            )
            // Depending on desired error handling, you might throw an exception or simply log and move on.
            // For now, just log.
        }

        return null // Return type is Void
    }
}
