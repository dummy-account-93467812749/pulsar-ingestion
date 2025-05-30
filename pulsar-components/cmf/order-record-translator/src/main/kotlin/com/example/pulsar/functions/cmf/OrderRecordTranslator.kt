package com.example.pulsar.functions.cmf

import com.example.pulsar.libs.CommonEvent
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.apache.pulsar.functions.api.Context
import org.apache.pulsar.functions.api.Function
import org.slf4j.Logger
import java.util.UUID

/**
 * A Pulsar Function that translates raw OrderRecord JSON messages into the standardized
 * [CommonEvent] schema.
 *
 * This function expects input messages representing order records. It extracts key fields,
 * such as `orderId` and `placedAt` (which is expected to be an ISO 8601 timestamp),
 * and maps them to the [CommonEvent] structure. The original order record data is
 * embedded in the `data` field of the [CommonEvent].
 */
class OrderRecordTranslator : Function<String, String> {

    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    private lateinit var log: Logger

    /**
     * Processes an input JSON string, expecting an OrderRecord format, and transforms it
     * into a [CommonEvent] JSON string.
     *
     * Expected input format:
     * ```json
     * {
     *   "orderId": "ORD-456",
     *   "items": ["item1", "item2"],
     *   "placedAt": "2025-05-26T10:00:00Z"
     * }
     * ```
     * Key transformations:
     * - The `placedAt` field (ISO 8601 string) is used directly as the `timestamp` for the [CommonEvent].
     * - A unique `eventId` (UUID) is generated.
     * - `source` is set to "order-service".
     * - `eventType` is set to "ORDER_EVENT".
     * - The original input JSON is embedded in the `data` field of the [CommonEvent].
     *
     * @param input The input JSON string representing an order record, potentially null.
     * @param context The Pulsar function context, providing access to logging and other resources.
     * @return A JSON string representing the transformed [CommonEvent], or null if processing
     *         fails (e.g., malformed input, missing required fields) or if the input is null.
     */
    override fun process(input: String?, context: Context?): String? {
        if (context == null) {
            println("Error: Context is null. Cannot initialize logger or process message.")
            return null
        }
        log = context.logger

        if (input == null) {
            log.warn("Received null input. Skipping.")
            return null
        }

        log.debug("Received OrderRecord input: {}", input)

        try {
            val inputJson = objectMapper.readTree(input)

            if (!inputJson.has("orderId") || !inputJson.has("placedAt")) {
                log.error("Missing required fields 'orderId' or 'placedAt' in OrderRecord input: {}", input)
                return null
            }

            val orderId = inputJson.get("orderId").asText()
            val timestamp = inputJson.get("placedAt").asText()

            val eventId = UUID.randomUUID().toString()
            val source = "order-service"
            val eventType = "ORDER_EVENT"
            val dataPayload: JsonNode = inputJson

            val commonEvent = CommonEvent(
                eventId = eventId,
                source = source,
                eventType = eventType,
                timestamp = timestamp,
                data = dataPayload,
            )

            val outputJson = objectMapper.writeValueAsString(commonEvent)
            log.info("Successfully transformed OrderRecord (orderId: {}) to CommonEvent (eventId: {})", orderId, eventId)
            return outputJson
        } catch (e: Exception) {
            log.error("Failed to process OrderRecord input: {}. Error: {}", input, e.message, e)
            return null
        }
    }
}
