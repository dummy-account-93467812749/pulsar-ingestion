package com.example.pulsar.functions.transforms.translators

import com.example.pulsar.common.CommonEvent
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.apache.pulsar.functions.api.Context
import org.apache.pulsar.functions.api.Function
import org.slf4j.Logger
import java.util.UUID

/**
 * A Pulsar Function that translates raw PaymentNotice JSON messages into the standardized
 * [CommonEvent] schema.
 *
 * This function expects input messages representing payment notices. It extracts key fields,
 * such as `txnId` and `time` (which is expected to be an ISO 8601 timestamp),
 * and maps them to the [CommonEvent] structure. The original payment notice data is
 * embedded in the `data` field of the [CommonEvent].
 */
class PaymentNoticeTranslator : Function<String, String> {

    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    private lateinit var log: Logger

    /**
     * Processes an input JSON string, expecting a PaymentNotice format, and transforms it
     * into a [CommonEvent] JSON string.
     *
     * Expected input format:
     * ```json
     * {
     *   "txnId": "TXN-001",
     *   "amount": 99.95,
     *   "currency": "USD",
     *   "time": "2025-05-26T09:30:00Z"
     * }
     * ```
     * Key transformations:
     * - The `time` field (ISO 8601 string) is used directly as the `timestamp` for the [CommonEvent].
     * - A unique `eventId` (UUID) is generated.
     * - `source` is set to "payment-gateway".
     * - `eventType` is set to "PAYMENT_EVENT".
     * - The original input JSON is embedded in the `data` field of the [CommonEvent].
     *
     * @param input The input JSON string representing a payment notice, potentially null.
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

        log.debug("Received PaymentNotice input: {}", input)

        try {
            val inputJson = objectMapper.readTree(input)

            if (!inputJson.has("txnId") || !inputJson.has("time")) {
                log.error("Missing required fields 'txnId' or 'time' in PaymentNotice input: {}", input)
                return null
            }

            val txnId = inputJson.get("txnId").asText()
            val timestamp = inputJson.get("time").asText() 

            val eventId = UUID.randomUUID().toString()
            val source = "payment-gateway"
            val eventType = "PAYMENT_EVENT"
            val dataPayload: JsonNode = inputJson

            val commonEvent = CommonEvent(
                eventId = eventId,
                source = source,
                eventType = eventType,
                timestamp = timestamp,
                data = dataPayload
            )

            val outputJson = objectMapper.writeValueAsString(commonEvent)
            log.info("Successfully transformed PaymentNotice (txnId: {}) to CommonEvent (eventId: {})", txnId, eventId)
            return outputJson

        } catch (e: Exception) {
            log.error("Failed to process PaymentNotice input: {}. Error: {}", input, e.message, e)
            return null
        }
    }
}
