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
 * A Pulsar Function that translates raw ShipmentStatus JSON messages into the standardized
 * [CommonEvent] schema.
 *
 * This function expects input messages representing shipment status updates. It extracts relevant
 * fields, converts the `deliveredAt` field (epoch seconds) to an ISO 8601 UTC timestamp,
 * and maps them to the [CommonEvent] structure. The original shipment status data is
 * embedded in the `data` field of the [CommonEvent].
 */
class ShipmentStatusTranslator : Function<String, String> {

    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    private lateinit var log: Logger
    private val isoFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC)

    /**
     * Processes an input JSON string, expecting a ShipmentStatus format, and transforms it
     * into a [CommonEvent] JSON string.
     *
     * Expected input format:
     * ```json
     * {
     *   "shipId": "SHIP-321",
     *   "status": "DELIVERED",
     *   "deliveredAt": 1620100000
     * }
     * ```
     * Key transformations:
     * - The `deliveredAt` field (epoch seconds) is converted to an ISO 8601 UTC timestamp.
     * - A unique `eventId` (UUID) is generated.
     * - `source` is set to "shipping-service".
     * - `eventType` is set to "SHIPMENT_EVENT".
     * - The original input JSON is embedded in the `data` field of the [CommonEvent].
     *
     * @param input The input JSON string representing a shipment status, potentially null.
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

        log.debug("Received ShipmentStatus input: {}", input)

        try {
            val inputJson = objectMapper.readTree(input)

            if (!inputJson.has("shipId") || !inputJson.has("deliveredAt")) {
                log.error("Missing required fields 'shipId' or 'deliveredAt' in ShipmentStatus input: {}", input)
                return null
            }

            val shipId = inputJson.get("shipId").asText()
            val deliveredAtEpoch = inputJson.get("deliveredAt").asLong()
            val timestamp = Instant.ofEpochSecond(deliveredAtEpoch).atOffset(ZoneOffset.UTC).format(isoFormatter)

            val eventId = UUID.randomUUID().toString()
            val source = "shipping-service"
            val eventType = "SHIPMENT_EVENT"
            val dataPayload: JsonNode = inputJson

            val commonEvent = CommonEvent(
                eventId = eventId,
                source = source,
                eventType = eventType,
                timestamp = timestamp,
                data = dataPayload,
            )

            val outputJson = objectMapper.writeValueAsString(commonEvent)
            log.info("Successfully transformed ShipmentStatus (shipId: {}) to CommonEvent (eventId: {})", shipId, eventId)
            return outputJson
        } catch (e: Exception) {
            log.error("Failed to process ShipmentStatus input: {}. Error: {}", input, e.message, e)
            return null
        }
    }
}
