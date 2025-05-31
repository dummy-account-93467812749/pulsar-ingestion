package com.example.pulsar.functions.cmf.translators

import com.example.pulsar.common.*
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
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

// Define the invented Geotab-specific input format
data class GeotabInputMessage(
    @JsonProperty("Device_ID") val deviceId: String,
    @JsonProperty("Vehicle_ID") val vehicleId: String,
    @JsonProperty("Record_DateTime") val recordDateTime: String, // Assuming ISO 8601 format "yyyy-MM-dd'T'HH:mm:ss.SSSX"
    @JsonProperty("Latitude") val latitude: Double,
    @JsonProperty("Longitude") val longitude: Double,
    @JsonProperty("Odometer_mi") val odometerMiles: Double? = null,
    @JsonProperty("EngineSpeed_rpm") val engineRpm: Double? = null,
    @JsonProperty("Fuel_Level_pct") val fuelLevelPct: Double? = null,
    @JsonProperty("Ignition_Status") val ignitionStatusStr: String? = null, // e.g., "ON", "OFF"
    // Add any other Geotab-specific fields here. These will go into sourceSpecificData.
    val customGeotabField1: String? = null,
    val customGeotabField2: Int? = null
)

/**
 * Translates "invented" Geotab-specific messages into the CommonMessageFormat.
 */
class GeotabTranslator : Function<String, String> {

    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    private lateinit var log: Logger
    private val isoFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC)

    override fun process(input: String?, context: Context?): String? {
        if (context == null) {
            println("Error: Context is null.") // Or use a fallback logger
            return null
        }
        log = context.logger

        if (input == null) {
            log.warn("Received null input. Skipping.")
            return null
        }

        log.debug("Received Geotab input: {}", input)

        try {
            val geotabMessage: GeotabInputMessage = objectMapper.readValue(input)

            val epochSource = Instant.parse(geotabMessage.recordDateTime).toEpochMilli()
            val formattedTimestamp = Instant.ofEpochMilli(epochSource).atOffset(ZoneOffset.UTC).format(isoFormatter)

            val telemetry = CommonTelemetry(
                location = CommonLocation(
                    timestamp = formattedTimestamp,
                    lat = geotabMessage.latitude,
                    lon = geotabMessage.longitude
                ),
                odometerCanMi = geotabMessage.odometerMiles,
                engineRpm = geotabMessage.engineRpm,
                fuelLevelPct = geotabMessage.fuelLevelPct,
                ignitionStatus = when (geotabMessage.ignitionStatusStr?.uppercase()) {
                    "ON" -> IgnitionStatus.ON
                    "OFF" -> IgnitionStatus.OFF
                    else -> IgnitionStatus.UNKNOWN
                }
                // Map other fields from GeotabInputMessage to CommonTelemetry as needed
            )

            // For sourceSpecificData, we can pass the whole input object or a subset
            // For this example, let's pass fields not directly mapped to CommonTelemetry
            val sourceSpecific = mapOf(
                "customGeotabField1" to geotabMessage.customGeotabField1,
                "customGeotabField2" to geotabMessage.customGeotabField2
            ).filterValues { it != null }


            val commonMessage = CommonMessageFormat(
                dateTime = formattedTimestamp,
                epochSource = epochSource,
                vehicleId = geotabMessage.vehicleId,
                deviceId = geotabMessage.deviceId,
                tenantId = context.tenant, // Example: using Pulsar tenant
                sourceType = SourceType.Geotab,
                partitionKey = geotabMessage.vehicleId, // Example: using vehicleId as partition key
                telemetry = telemetry,
                events = null, // Populate if Geotab input has event data
                sourceSpecificData = sourceSpecific,
                meta = CommonMeta(additionalProperties = mapOf("translator" to "GeotabTranslator_v1.0"))
            )

            val outputJson = objectMapper.writeValueAsString(commonMessage)
            log.info("Successfully transformed Geotab message for vehicle {} to CMF (epoch: {})", commonMessage.vehicleId, commonMessage.epochSource)
            return outputJson

        } catch (e: Exception) {
            log.error("Failed to process Geotab input: {}. Error: {}", input, e.message, e)
            return null
        }
    }
}
