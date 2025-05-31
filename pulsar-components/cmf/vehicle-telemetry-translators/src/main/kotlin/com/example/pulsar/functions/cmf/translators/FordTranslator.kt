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

/**
 * Represents the structure of an "invented" Ford-specific input message.
 * This data class is used for demonstration and testing purposes, defining a potential input format
 * that the [FordTranslator] can process. Real Ford messages (e.g., from Ford Pro Telematics) may have a different structure.
 *
 * @property vin The Vehicle Identification Number. Maps to `CommonMessageFormat.vehicleId`.
 * @property esn The Electronic Serial Number of the telematics device. Maps to `CommonMessageFormat.deviceId`.
 * @property captureTime The timestamp of the main event or record capture, in epoch milliseconds. Maps to `CommonMessageFormat.dateTime` (converted to ISO 8601) and `CommonMessageFormat.epochSource`.
 * @property coordinates The geographic coordinates of the vehicle. See [FordCoordinates].
 * @property vehicleSpeedMph The vehicle's speed in miles per hour. Maps to `CommonTelemetry.speedGpsMph`.
 * @property fuelGallons The remaining fuel in gallons. Maps to `CommonTelemetry.fuelLevelGallon`.
 * @property currentRpm The current engine RPM. Maps to `CommonTelemetry.engineRpm`.
 * @property fordExtraData A map for any additional Ford-specific key-value pairs. Populates `CommonMessageFormat.sourceSpecificData`.
 */
data class FordInputMessage(
    @JsonProperty("vin") val vin: String,
    @JsonProperty("esn") val esn: String,
    @JsonProperty("captureTime") val captureTime: Long, // Epoch milliseconds
    @JsonProperty("coords") val coordinates: FordCoordinates,
    @JsonProperty("vehicleSpeed") val vehicleSpeedMph: Double? = null,
    @JsonProperty("fuelRemainingGallons") val fuelGallons: Double? = null,
    @JsonProperty("rpm") val currentRpm: Int? = null,
    val fordExtraData: Map<String, Any>? = null // Catches other fields for sourceSpecificData
)

/**
 * Represents geographic coordinates for the [FordInputMessage].
 *
 * @property latitude The latitude coordinate.
 * @property longitude The longitude coordinate.
 * @property locationTimestamp The timestamp of when this specific location was recorded, in epoch milliseconds. Maps to `CommonTelemetry.location.timestamp`.
 */
data class FordCoordinates(
    @JsonProperty("latValue") val latitude: Double,
    @JsonProperty("lonValue") val longitude: Double,
    @JsonProperty("ts") val locationTimestamp: Long // Epoch milliseconds for location
)

/**
 * A Pulsar Function to translate Ford-specific vehicle telemetry messages into the [CommonMessageFormat].
 *
 * This translator consumes messages from a designated input topic (typically `raw-http-events` for Ford data)
 * which are expected to be JSON strings conforming to the [FordInputMessage] structure.
 *
 * **Expected Input JSON Format (example based on [FordInputMessage]):**
 * ```json
 * {
 *   "vin": "FORDVIN123456789",
 *   "esn": "FORDSN001",
 *   "captureTime": 1698318000000,
 *   "coords": {
 *     "latValue": 40.7128,
 *     "lonValue": -74.0060,
 *     "ts": 1698317995000
 *   },
 *   "vehicleSpeed": 65.5,
 *   "fuelRemainingGallons": 10.2,
 *   "rpm": 2200,
 *   "fordExtraData": {
 *     "tirePressureStatus": "Normal",
 *     "oilLife": "85%"
 *   }
 * }
 * ```
 *
 * **Mapping to [CommonMessageFormat]:**
 * - `vin` -> `CommonMessageFormat.vehicleId`, `CommonMessageFormat.partitionKey`
 * - `esn` -> `CommonMessageFormat.deviceId`
 * - `captureTime` -> `CommonMessageFormat.dateTime` (converted from epoch ms to ISO 8601), `CommonMessageFormat.epochSource`
 * - `coords.latValue`, `coords.lonValue`, `coords.ts` -> `CommonTelemetry.location` (timestamp converted)
 * - `vehicleSpeed` -> `CommonTelemetry.speedGpsMph`
 * - `fuelRemainingGallons` -> `CommonTelemetry.fuelLevelGallon`
 * - `rpm` -> `CommonTelemetry.engineRpm`
 * - `fordExtraData` map is placed into `CommonMessageFormat.sourceSpecificData`.
 *
 * The translator populates `CommonMessageFormat.sourceType` with [SourceType.Ford].
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
