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
 * Represents the structure of an "invented" CalAmp-specific input message.
 * This data class is used for demonstration and testing purposes, defining a potential input format
 * that the [CalAmpTranslator] can process. Real CalAmp messages may have a different structure.
 *
 * @property unitId The unique identifier of the CalAmp unit/device. Maps to `CommonMessageFormat.deviceId`.
 * @property vehicleIdentifier The unique identifier of the vehicle, often the VIN. Maps to `CommonMessageFormat.vehicleId`.
 * @property messageTimestamp The timestamp of the message in epoch seconds. Maps to `CommonMessageFormat.dateTime` (converted to ISO 8601) and `CommonMessageFormat.epochSource` (converted to milliseconds).
 * @property gpsLat The latitude coordinate of the vehicle's location. Maps to `CommonTelemetry.location.lat`.
 * @property gpsLon The longitude coordinate of the vehicle's location. Maps to `CommonTelemetry.location.lon`.
 * @property speedMph The vehicle's speed in miles per hour. Maps to `CommonTelemetry.speedGpsMph`.
 * @property fuelPercent The fuel level as a percentage. Maps to `CommonTelemetry.fuelLevelPct`.
 * @property batteryVoltage The vehicle's battery voltage. Maps to `CommonTelemetry.batteryVoltage`.
 * @property calAmpSpecificValue An example custom field specific to CalAmp. Populates `CommonMessageFormat.sourceSpecificData`.
 */
data class CalAmpInputMessage(
    @JsonProperty("unit_id") val unitId: String,
    @JsonProperty("vid") val vehicleIdentifier: String, // Typically VIN
    @JsonProperty("msg_ts") val messageTimestamp: Long, // Epoch seconds
    @JsonProperty("gps_lat") val gpsLat: Double,
    @JsonProperty("gps_lon") val gpsLon: Double,
    @JsonProperty("speed_mph") val speedMph: Double? = null,
    @JsonProperty("fuel_percent") val fuelPercent: Double? = null,
    @JsonProperty("voltage") val batteryVoltage: Double? = null,
    // Example of a field that would go into sourceSpecificData
    val calAmpSpecificValue: String? = null
)

/**
 * A Pulsar Function to translate CalAmp-specific vehicle telemetry messages into the [CommonMessageFormat].
 *
 * This translator consumes messages from a designated input topic (typically `raw-kafka-events` for CalAmp data)
 * which are expected to be JSON strings conforming to the [CalAmpInputMessage] structure.
 *
 * **Expected Input JSON Format (example based on [CalAmpInputMessage]):**
 * ```json
 * {
 *   "unit_id": "calamp-dev-789",
 *   "vid": "TESTVIN9876543210",
 *   "msg_ts": 1698314400,
 *   "gps_lat": 35.6895,
 *   "gps_lon": 139.6917,
 *   "speed_mph": 55.0,
 *   "fuel_percent": 60.2,
 *   "voltage": 12.5,
 *   "calAmpSpecificValue": "CalAmpExtraInfo"
 * }
 * ```
 *
 * **Mapping to [CommonMessageFormat]:**
 * - `unit_id` -> `CommonMessageFormat.deviceId`
 * - `vid` -> `CommonMessageFormat.vehicleId`, `CommonMessageFormat.partitionKey`
 * - `msg_ts` -> `CommonMessageFormat.dateTime` (converted from epoch seconds to ISO 8601), `CommonMessageFormat.epochSource` (converted to epoch ms)
 * - `gps_lat`, `gps_lon` -> `CommonTelemetry.location`
 * - `speed_mph` -> `CommonTelemetry.speedGpsMph`
 * - `fuel_percent` -> `CommonTelemetry.fuelLevelPct`
 * - `voltage` -> `CommonTelemetry.batteryVoltage`
 * - `calAmpSpecificValue` and any other non-mapped fields from `CalAmpInputMessage` are placed into `CommonMessageFormat.sourceSpecificData`.
 *
 * The translator populates `CommonMessageFormat.sourceType` with [SourceType.CalAmp].
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
