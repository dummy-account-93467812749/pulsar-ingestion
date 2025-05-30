package com.example.pulsar.functions.cmf

import com.example.pulsar.libs.CommonEvent
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.apache.pulsar.functions.api.Context
import org.apache.pulsar.functions.api.Function
import org.slf4j.Logger
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * A Pulsar Function that translates raw InventoryUpdate JSON messages into the standardized
 * [CommonEvent] schema.
 *
 * This function expects input messages representing inventory updates. It extracts relevant fields,
 * converts the `updateTime` (epoch seconds) to an ISO 8601 UTC timestamp, and maps them
 * to the [CommonEvent] structure. The original inventory update data is embedded in the
 * `data` field of the [CommonEvent].
 */
class InventoryUpdateTranslator : Function<String, String> {

    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    private lateinit var log: Logger
    private val isoFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC)

    /**
     * Processes an input JSON string, expecting an InventoryUpdate format, and transforms it
     * into a [CommonEvent] JSON string.
     *
     * Expected input format:
     * ```json
     * {
     *   "sku": "SKU-789",
     *   "qty": 42,
     *   "updateTime": 1620050000
     * }
     * ```
     * Key transformations:
     * - The `updateTime` field (epoch seconds) is converted to an ISO 8601 UTC timestamp.
     * - A unique `eventId` (UUID) is generated.
     * - `source` is set to "inventory-service".
     * - `eventType` is set to "INVENTORY_EVENT".
     * - The original input JSON is embedded in the `data` field of the [CommonEvent].
     *
     * @param input The input JSON string representing an inventory update, potentially null.
     * @param context The Pulsar function context, providing access to logging and other resources.
     * @return A JSON string representing the transformed [CommonEvent], or null if processing
     *         fails (e.g., malformed input, missing required fields) or if the input is null.
     */
    override fun process(input: String?, context: Context?): String? {
        if (context == null) {
            println("Error: Context is null.")
            return null
        }
        log = context.logger

        if (input == null) {
            log.warn("Received null input. Skipping.")
            return null
        }

        log.debug("Received InventoryUpdate input: {}", input)

        try {
            val inputJson = objectMapper.readTree(input)

            if (!inputJson.has("sku") || !inputJson.has("updateTime")) {
                log.error("Missing required fields 'sku' or 'updateTime' in InventoryUpdate input: {}", input)
                return null
            }

            val sku = inputJson.get("sku").asText()
            val updateTimeEpoch = inputJson.get("updateTime").asLong()
            val timestamp = Instant.ofEpochSecond(updateTimeEpoch).atOffset(ZoneOffset.UTC).format(isoFormatter)

            val eventId = UUID.randomUUID().toString()
            val source = "inventory-service"
            val eventType = "INVENTORY_EVENT"
            val dataPayload: JsonNode = inputJson

            val commonEvent = CommonEvent(
                eventId = eventId,
                source = source,
                eventType = eventType,
                timestamp = timestamp,
                data = dataPayload,
            )

            val outputJson = objectMapper.writeValueAsString(commonEvent)
            log.info("Successfully transformed InventoryUpdate (sku: {}) to CommonEvent (eventId: {})", sku, eventId)
            return outputJson
        } catch (e: Exception) {
            log.error("Failed to process InventoryUpdate input: {}. Error: {}", input, e.message, e)
            return null
        }
    }
}
