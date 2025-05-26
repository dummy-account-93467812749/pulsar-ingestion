package com.example.pulsar.functions.transforms.translators

import com.example.pulsar.common.CommonEvent
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
 * A Pulsar Function that translates raw UserProfile JSON messages into the standardized
 * [CommonEvent] schema.
 *
 * This function expects input messages representing user profiles, extracts relevant fields,
 * and maps them to the [CommonEvent] structure. The original user profile data is
 * embedded in the `data` field of the [CommonEvent].
 */
class UserProfileTranslator : Function<String, String> {

    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    private lateinit var log: Logger
    private val isoFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC)

    /**
     * Processes an input JSON string, expecting a UserProfile format, and transforms it
     * into a [CommonEvent] JSON string.
     *
     * Expected input format:
     * ```json
     * {
     *   "uid": 123,
     *   "name": "Alice",
     *   "created": 1620000000
     * }
     * ```
     * Key transformations:
     * - The `created` field (epoch seconds) is converted to an ISO 8601 UTC timestamp.
     * - A unique `eventId` (UUID) is generated.
     * - `source` is set to "user-service".
     * - `eventType` is set to "USER_PROFILE_EVENT".
     * - The original input JSON is embedded in the `data` field of the [CommonEvent].
     *
     * @param input The input JSON string representing a user profile, potentially null.
     * @param context The Pulsar function context, providing access to logging and other resources.
     * @return A JSON string representing the transformed [CommonEvent], or null if processing
     *         fails (e.g., malformed input, missing required fields) or if the input is null.
     */
    override fun process(input: String?, context: Context?): String? {
        if (context == null) {
            println("Error: Context is null. Cannot process message.")
            return null
        }
        log = context.logger

        if (input == null) {
            log.warn("Received null input. Skipping.")
            return null
        }

        log.debug("Received UserProfile input: {}", input)

        try {
            val inputJson = objectMapper.readTree(input)

            if (!inputJson.has("uid") || !inputJson.has("created")) {
                log.error("Missing required fields 'uid' or 'created' in UserProfile input: {}", input)
                return null
            }

            val userId = inputJson.get("uid").asText()
            val createdEpochSeconds = inputJson.get("created").asLong()
            val timestamp = Instant.ofEpochSecond(createdEpochSeconds).atOffset(ZoneOffset.UTC).format(isoFormatter)

            val eventId = UUID.randomUUID().toString()
            val source = "user-service"
            val eventType = "USER_PROFILE_EVENT"
            val dataPayload: JsonNode = inputJson

            val commonEvent = CommonEvent(
                eventId = eventId,
                source = source,
                eventType = eventType,
                timestamp = timestamp,
                data = dataPayload
            )

            val outputJson = objectMapper.writeValueAsString(commonEvent)
            log.info("Successfully transformed UserProfile (uid: {}) to CommonEvent (eventId: {})", userId, eventId)
            return outputJson

        } catch (e: Exception) {
            log.error("Failed to process UserProfile input: {}. Error: {}", input, e.message, e)
            return null
        }
    }
}
