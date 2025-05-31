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

// Define the invented Ford-specific input format
data class FordInputMessage(
    @JsonProperty("vin") val vin: String, // Vehicle Identification Number
    @JsonProperty("esn") val esn: String, // Electronic Serial Number of the device
    @JsonProperty("captureTime") val captureTime: Long, // Epoch milliseconds
    @JsonProperty("coords") val coordinates: FordCoordinates,
    @JsonProperty("vehicleSpeed") val vehicleSpeedMph: Double? = null,
    @JsonProperty("fuelRemainingGallons") val fuelGallons: Double? = null,
    @JsonProperty("rpm") val currentRpm: Int? = null,
    // Add any other Ford-specific fields here
    val fordExtraData: Map<String, Any>? = null
)

data class FordCoordinates(
    @JsonProperty("latValue") val latitude: Double,
    @JsonProperty("lonValue") val longitude: Double,
    @JsonProperty("ts") val locationTimestamp: Long // Epoch milliseconds for location
)

/**
 * Translates "invented" Ford-specific messages into the CommonMessageFormat.
 */
class FordTranslator : Function<String, String> {

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

        log.debug("Received Ford input: {}", input)

        try {
            val fordMessage: FordInputMessage = objectMapper.readValue(input)

            val mainTimestamp = Instant.ofEpochMilli(fordMessage.captureTime).atOffset(ZoneOffset.UTC).format(isoFormatter)
            val locationTimestamp = Instant.ofEpochMilli(fordMessage.coordinates.locationTimestamp).atOffset(ZoneOffset.UTC).format(isoFormatter)

            val telemetry = CommonTelemetry(
                location = CommonLocation(
                    timestamp = locationTimestamp,
                    lat = fordMessage.coordinates.latitude,
                    lon = fordMessage.coordinates.longitude
                ),
                speedGpsMph = fordMessage.vehicleSpeedMph, // Assuming GPS speed
                fuelLevelGallon = fordMessage.fuelGallons,
                engineRpm = fordMessage.currentRpm?.toDouble()
                // Map other fields from FordInputMessage to CommonTelemetry as needed
            )

            val sourceSpecific = fordMessage.fordExtraData ?: emptyMap()

            val commonMessage = CommonMessageFormat(
                dateTime = mainTimestamp,
                epochSource = fordMessage.captureTime,
                vehicleId = fordMessage.vin,
                deviceId = fordMessage.esn,
                tenantId = context.tenant,
                sourceType = SourceType.Ford,
                partitionKey = fordMessage.vin,
                telemetry = telemetry,
                events = null, // Populate if Ford input has event data
                sourceSpecificData = sourceSpecific,
                meta = CommonMeta(additionalProperties = mapOf("translator" to "FordTranslator_v1.0"))
            )

            val outputJson = objectMapper.writeValueAsString(commonMessage)
            log.info("Successfully transformed Ford message for vehicle {} to CMF (epoch: {})", commonMessage.vehicleId, commonMessage.epochSource)
            return outputJson

        } catch (e: Exception) {
            log.error("Failed to process Ford input: {}. Error: {}", input, e.message, e)
            return null
        }
    }
}
