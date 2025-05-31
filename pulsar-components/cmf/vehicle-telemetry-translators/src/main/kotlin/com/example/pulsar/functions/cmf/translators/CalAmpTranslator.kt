package com.example.pulsar.functions.cmf.translators

import com.example.pulsar.common.*
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.apache.pulsar.functions.api.Context
import org.apache.pulsar.functions.api.Function
import org.slf4j.Logger
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

// Define the invented CalAmp-specific input format
data class CalAmpInputMessage(
    @JsonProperty("unit_id") val unitId: String,
    @JsonProperty("vid") val vehicleIdentifier: String,
    @JsonProperty("msg_ts") val messageTimestamp: Long, // Epoch seconds
    @JsonProperty("gps_lat") val gpsLat: Double,
    @JsonProperty("gps_lon") val gpsLon: Double,
    @JsonProperty("speed_mph") val speedMph: Double? = null,
    @JsonProperty("fuel_percent") val fuelPercent: Double? = null,
    @JsonProperty("voltage") val batteryVoltage: Double? = null,
    // Add any other CalAmp-specific fields here
    val calAmpSpecificValue: String? = null
)

/**
 * Translates "invented" CalAmp-specific messages into the CommonMessageFormat.
 */
class CalAmpTranslator : Function<String, String> {

    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    private lateinit var log: Logger
    private val isoFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC)

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

        log.debug("Received CalAmp input: {}", input)

        try {
            val calAmpMessage: CalAmpInputMessage = objectMapper.readValue(input)

            val epochSourceMillis = calAmpMessage.messageTimestamp * 1000 // Convert seconds to millis
            val formattedTimestamp = Instant.ofEpochMilli(epochSourceMillis).atOffset(ZoneOffset.UTC).format(isoFormatter)

            val telemetry = CommonTelemetry(
                location = CommonLocation(
                    timestamp = formattedTimestamp,
                    lat = calAmpMessage.gpsLat,
                    lon = calAmpMessage.gpsLon
                ),
                speedGpsMph = calAmpMessage.speedMph,
                fuelLevelPct = calAmpMessage.fuelPercent,
                batteryVoltage = calAmpMessage.batteryVoltage
                // Map other fields from CalAmpInputMessage to CommonTelemetry as needed
            )

            val sourceSpecific = mapOf(
                "calAmpSpecificValue" to calAmpMessage.calAmpSpecificValue
            ).filterValues { it != null }

            val commonMessage = CommonMessageFormat(
                dateTime = formattedTimestamp,
                epochSource = epochSourceMillis,
                vehicleId = calAmpMessage.vehicleIdentifier,
                deviceId = calAmpMessage.unitId,
                tenantId = context.tenant,
                sourceType = SourceType.CalAmp,
                partitionKey = calAmpMessage.vehicleIdentifier,
                telemetry = telemetry,
                events = null, // Populate if CalAmp input has event data
                sourceSpecificData = sourceSpecific,
                meta = CommonMeta(additionalProperties = mapOf("translator" to "CalAmpTranslator_v1.0"))
            )

            val outputJson = objectMapper.writeValueAsString(commonMessage)
            log.info("Successfully transformed CalAmp message for vehicle {} to CMF (epoch: {})", commonMessage.vehicleId, commonMessage.epochSource)
            return outputJson

        } catch (e: Exception) {
            log.error("Failed to process CalAmp input: {}. Error: {}", input, e.message, e)
            return null
        }
    }
}
