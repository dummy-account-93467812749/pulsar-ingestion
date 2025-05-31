package com.example.pulsar.functions.cmf.translators

import com.example.pulsar.common.CommonMessageFormat
import com.example.pulsar.common.IgnitionStatus
import com.example.pulsar.common.SourceType
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.apache.pulsar.functions.api.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.slf4j.Logger
import java.time.Instant

class GeotabTranslatorTest {

    private lateinit var translator: GeotabTranslator
    private lateinit var mockContext: Context
    private lateinit var mockLogger: Logger
    private val objectMapper = jacksonObjectMapper()

    @BeforeEach
    fun setUp() {
        translator = GeotabTranslator()
        mockContext = Mockito.mock(Context::class.java)
        mockLogger = Mockito.mock(Logger::class.java)
        Mockito.`when`(mockContext.logger).thenReturn(mockLogger)
        Mockito.`when`(mockContext.tenant).thenReturn("test-tenant")
    }

    @Test
    fun `process valid Geotab input should return correct CMF JSON`() {
        val inputJson = """
        {
            "Device_ID": "geo-device-123",
            "Vehicle_ID": "vehicle-abc",
            "Record_DateTime": "2023-10-26T10:00:00.000Z",
            "Latitude": 34.0522,
            "Longitude": -118.2437,
            "Odometer_mi": 12345.6,
            "EngineSpeed_rpm": 2500.0,
            "Fuel_Level_pct": 75.5,
            "Ignition_Status": "ON",
            "customGeotabField1": "customValue1",
            "customGeotabField2": 100
        }
        """.trimIndent()

        val expectedEpoch = Instant.parse("2023-10-26T10:00:00.000Z").toEpochMilli()

        val resultJson = translator.process(inputJson, mockContext)
        assertNotNull(resultJson)

        val resultCmf = objectMapper.readValue(resultJson, CommonMessageFormat::class.java)

        assertEquals("vehicle-abc", resultCmf.vehicleId)
        assertEquals("geo-device-123", resultCmf.deviceId)
        assertEquals(SourceType.Geotab, resultCmf.sourceType)
        assertEquals(expectedEpoch, resultCmf.epochSource)
        assertEquals("2023-10-26T10:00:00Z", resultCmf.dateTime) // Check ISO formatter output
        assertEquals("test-tenant", resultCmf.tenantId)
        assertEquals("vehicle-abc", resultCmf.partitionKey)

        assertNotNull(resultCmf.telemetry)
        val telemetry = resultCmf.telemetry!!
        assertEquals(34.0522, telemetry.location?.lat)
        assertEquals(-118.2437, telemetry.location?.lon)
        assertEquals("2023-10-26T10:00:00Z", telemetry.location?.timestamp)
        assertEquals(12345.6, telemetry.odometerCanMi)
        assertEquals(2500.0, telemetry.engineRpm)
        assertEquals(75.5, telemetry.fuelLevelPct)
        assertEquals(IgnitionStatus.ON, telemetry.ignitionStatus)

        assertNotNull(resultCmf.sourceSpecificData)
        @Suppress("UNCHECKED_CAST")
        val sourceSpecific = resultCmf.sourceSpecificData as Map<String, Any>
        assertEquals("customValue1", sourceSpecific["customGeotabField1"])
        assertEquals(100, (sourceSpecific["customGeotabField2"] as Number).toInt())

        assertNotNull(resultCmf.meta)
        assertEquals(mapOf("translator" to "GeotabTranslator_v1.0"), resultCmf.meta?.additionalProperties)

        Mockito.verify(mockLogger).info(Mockito.contains("Successfully transformed Geotab message"), Mockito.anyString(), Mockito.anyLong())
    }

    @Test
    fun `process Geotab input with missing optional fields should succeed`() {
        val inputJson = """
        {
            "Device_ID": "geo-device-789",
            "Vehicle_ID": "vehicle-xyz",
            "Record_DateTime": "2023-10-27T12:00:00.000Z",
            "Latitude": 35.0000,
            "Longitude": -119.0000
        }
        """.trimIndent() // Odometer, RPM, Fuel, Ignition, custom fields are missing

        val resultJson = translator.process(inputJson, mockContext)
        assertNotNull(resultJson)
        val resultCmf = objectMapper.readValue(resultJson, CommonMessageFormat::class.java)

        assertEquals("vehicle-xyz", resultCmf.vehicleId)
        assertEquals(SourceType.Geotab, resultCmf.sourceType)
        assertNotNull(resultCmf.telemetry)
        val telemetry = resultCmf.telemetry!!
        assertNull(telemetry.odometerCanMi)
        assertNull(telemetry.engineRpm)
        assertNull(telemetry.fuelLevelPct)
        assertEquals(IgnitionStatus.UNKNOWN, telemetry.ignitionStatus) // Default when not provided

        @Suppress("UNCHECKED_CAST")
        val sourceSpecific = resultCmf.sourceSpecificData as Map<String, Any>
        assertTrue(sourceSpecific.isEmpty())
    }

    @Test
    fun `process malformed Geotab input should return null and log error`() {
        val inputJson = """{"Device_ID": "test"}""" // Missing vehicle_id, timestamp, lat, lon

        val resultJson = translator.process(inputJson, mockContext)
        assertNull(resultJson)
        Mockito.verify(mockLogger).error(Mockito.contains("Failed to process Geotab input"), Mockito.anyString(), Mockito.anyString(), Mockito.any(Exception::class.java))
    }

    @Test
    fun `process Geotab input with unknown ignition status`() {
        val inputJson = """
        {
            "Device_ID": "geo-device-123",
            "Vehicle_ID": "vehicle-abc",
            "Record_DateTime": "2023-10-26T10:00:00.000Z",
            "Latitude": 34.0522,
            "Longitude": -118.2437,
            "Ignition_Status": "AJAR"
        }
        """.trimIndent()

        val resultJson = translator.process(inputJson, mockContext)
        assertNotNull(resultJson)
        val resultCmf = objectMapper.readValue(resultJson, CommonMessageFormat::class.java)
        assertEquals(IgnitionStatus.UNKNOWN, resultCmf.telemetry?.ignitionStatus)
    }

    @Test
    fun `process null input should return null and log warning`() {
        val result = translator.process(null, mockContext)
        assertNull(result)
        Mockito.verify(mockLogger).warn("Received null input. Skipping.")
    }
}
