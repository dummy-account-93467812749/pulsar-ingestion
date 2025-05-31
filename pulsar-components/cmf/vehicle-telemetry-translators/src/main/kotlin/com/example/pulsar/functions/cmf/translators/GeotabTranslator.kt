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

/**
 * Represents the structure of an "invented" Geotab-specific input message.
 * This data class is used for demonstration and testing purposes, defining a potential input format
 * that the [GeotabTranslator] can process. Real Geotab messages may have a different structure.
 *
 * @property deviceId The unique identifier of the Geotab device. Maps to `CommonMessageFormat.deviceId`.
 * @property vehicleId The unique identifier of the vehicle, often the VIN. Maps to `CommonMessageFormat.vehicleId`.
 * @property recordDateTime The timestamp of the record in ISO 8601 format (e.g., "2023-10-26T10:00:00Z"). Maps to `CommonMessageFormat.dateTime`.
 * @property latitude The latitude coordinate of the vehicle's location. Maps to `CommonTelemetry.location.lat`.
 * @property longitude The longitude coordinate of the vehicle's location. Maps to `CommonTelemetry.location.lon`.
 * @property odometerMiles The vehicle's odometer reading in miles. Maps to `CommonTelemetry.odometerCanMi`.
 * @property engineRpm The engine speed in revolutions per minute. Maps to `CommonTelemetry.engineRpm`.
 * @property fuelLevelPct The fuel level as a percentage. Maps to `CommonTelemetry.fuelLevelPct`.
 * @property ignitionStatusStr The ignition status as a string (e.g., "ON", "OFF"). Maps to `CommonTelemetry.ignitionStatus`.
 * @property customGeotabField1 An example custom field specific to Geotab. Populates `CommonMessageFormat.sourceSpecificData`.
 * @property customGeotabField2 Another example custom field specific to Geotab. Populates `CommonMessageFormat.sourceSpecificData`.
 */
data class GeotabInputMessage(
    @JsonProperty("Device_ID") val deviceId: String,
    @JsonProperty("Vehicle_ID") val vehicleId: String, // Typically VIN
    @JsonProperty("Record_DateTime") val recordDateTime: String, // ISO 8601 format "yyyy-MM-dd'T'HH:mm:ssZ" or similar
    @JsonProperty("Latitude") val latitude: Double,
    @JsonProperty("Longitude") val longitude: Double,
    @JsonProperty("Odometer_mi") val odometerMiles: Double? = null,
    @JsonProperty("EngineSpeed_rpm") val engineRpm: Double? = null,
    @JsonProperty("Fuel_Level_pct") val fuelLevelPct: Double? = null,
    @JsonProperty("Ignition_Status") val ignitionStatusStr: String? = null, // e.g., "ON", "OFF", "true", "false"
    // Example of fields that would go into sourceSpecificData
    val customGeotabField1: String? = null,
    val customGeotabField2: Int? = null
)

/**
 * A Pulsar Function to translate Geotab-specific vehicle telemetry messages into the [CommonMessageFormat].
 *
 * This translator consumes messages from a designated input topic (typically `raw-kinesis-events` for Geotab data)
 * which are expected to be JSON strings conforming to the [GeotabInputMessage] structure.
 *
 * **Expected Input JSON Format (example based on [GeotabInputMessage]):**
 * ```json
 * {
 *   "Device_ID": "g12345",
 *   "Vehicle_ID": "TESTVIN1234567890",
 *   "Record_DateTime": "2023-10-27T14:30:00Z",
 *   "Latitude": 34.0522,
 *   "Longitude": -118.2437,
 *   "Odometer_mi": 12345.6,
 *   "EngineSpeed_rpm": 1500.0,
 *   "Fuel_Level_pct": 75.5,
 *   "Ignition_Status": "ON",
 *   "customGeotabField1": "someValue",
 *   "customGeotabField2": 100
 * }
 * ```
 *
 * **Mapping to [CommonMessageFormat]:**
 * - `Device_ID` -> `CommonMessageFormat.deviceId`
 * - `Vehicle_ID` -> `CommonMessageFormat.vehicleId`, `CommonMessageFormat.partitionKey`
 * - `Record_DateTime` -> `CommonMessageFormat.dateTime`, `CommonMessageFormat.epochSource` (converted to epoch ms)
 * - `Latitude`, `Longitude` -> `CommonTelemetry.location`
 * - `Odometer_mi` -> `CommonTelemetry.odometerCanMi`
 * - `EngineSpeed_rpm` -> `CommonTelemetry.engineRpm`
 * - `Fuel_Level_pct` -> `CommonTelemetry.fuelLevelPct`
 * - `Ignition_Status` -> `CommonTelemetry.ignitionStatus` (parsed from string to enum)
 * - Other fields like `customGeotabField1`, `customGeotabField2` are placed into `CommonMessageFormat.sourceSpecificData`.
 *
 * The translator populates `CommonMessageFormat.sourceType` with [SourceType.Geotab].
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
