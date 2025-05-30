package com.example.pulsar.filterer
import com.example.pulsar.libs.CommonEvent
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.apache.pulsar.client.api.Schema
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

        // Sanitize eventType for use in topic names: lowercase and replace any non-alphanumeric with hyphens
        val sanitizedEventType = commonEvent.eventType
            .lowercase()
            .replace(Regex("[^a-z0-9]"), "-")

        // Construct target topic name
        val targetTopic = "persistent://public/default/fn-split-$sanitizedEventType"

        try {
            // Asynchronously send the original JSON payload
            context.newOutputMessage(targetTopic, Schema.BYTES)
                .value(input.toByteArray())
                .sendAsync()

            log.info(
                "Routed eventId: {} (type: {}) to topic: {}",
                commonEvent.eventId,
                commonEvent.eventType,
                targetTopic,
            )
        } catch (e: Exception) {
            log.error(
                "Failed to send message for eventId: {} (type: {}) to topic: {}. Error: {}",
                commonEvent.eventId,
                commonEvent.eventType,
                targetTopic,
                e.message,
                e,
            )
        }

        return null
    }
}
