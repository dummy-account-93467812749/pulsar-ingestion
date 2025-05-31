package com.example.pulsar.functions.cmf.translators

import com.example.pulsar.common.CommonMessageFormat
import com.example.pulsar.common.SourceType
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.apache.pulsar.functions.api.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.slf4j.Logger
import java.time.Instant

class CalAmpTranslatorTest {

    private lateinit var translator: CalAmpTranslator
    private lateinit var mockContext: Context
    private lateinit var mockLogger: Logger
    private val objectMapper = jacksonObjectMapper()

    @BeforeEach
    fun setUp() {
        translator = CalAmpTranslator()
        mockContext = Mockito.mock(Context::class.java)
        mockLogger = Mockito.mock(Logger::class.java)
        Mockito.`when`(mockContext.logger).thenReturn(mockLogger)
        Mockito.`when`(mockContext.tenant).thenReturn("calamp-tenant")
    }

    @Test
    fun `process valid CalAmp input should return correct CMF JSON`() {
        val inputTimestampSeconds = Instant.parse("2023-11-01T08:30:00.000Z").epochSecond
        val inputJson = """
        {
            "unit_id": "calamp-unit-001",
            "vid": "calamp-vehicle-777",
            "msg_ts": $inputTimestampSeconds,
            "gps_lat": 36.1699,
            "gps_lon": -115.1398,
            "speed_mph": 65.5,
            "fuel_percent": 45.0,
            "voltage": 12.5,
            "calAmpSpecificValue": "payload-data"
        }
        """.trimIndent()

        val expectedEpochMillis = inputTimestampSeconds * 1000

        val resultJson = translator.process(inputJson, mockContext)
        assertNotNull(resultJson)

        val resultCmf = objectMapper.readValue(resultJson, CommonMessageFormat::class.java)

        assertEquals("calamp-vehicle-777", resultCmf.vehicleId)
        assertEquals("calamp-unit-001", resultCmf.deviceId)
        assertEquals(SourceType.CalAmp, resultCmf.sourceType)
        assertEquals(expectedEpochMillis, resultCmf.epochSource)
        // Example: "2023-11-01T08:30:00Z"
        assertTrue(resultCmf.dateTime.startsWith("2023-11-01T08:30:00"))
        assertEquals("calamp-tenant", resultCmf.tenantId)
        assertEquals("calamp-vehicle-777", resultCmf.partitionKey)

        assertNotNull(resultCmf.telemetry)
        val telemetry = resultCmf.telemetry!!
        assertEquals(36.1699, telemetry.location?.lat)
        assertEquals(-115.1398, telemetry.location?.lon)
        assertEquals(65.5, telemetry.speedGpsMph)
        assertEquals(45.0, telemetry.fuelLevelPct)
        assertEquals(12.5, telemetry.batteryVoltage)

        assertNotNull(resultCmf.sourceSpecificData)
        @Suppress("UNCHECKED_CAST")
        val sourceSpecific = resultCmf.sourceSpecificData as Map<String, Any>
        assertEquals("payload-data", sourceSpecific["calAmpSpecificValue"])

        Mockito.verify(mockLogger).info(Mockito.contains("Successfully transformed CalAmp message"), Mockito.anyString(), Mockito.anyLong())
    }

    @Test
    fun `process CalAmp input with missing optional fields should succeed`() {
        val inputTimestampSeconds = Instant.parse("2023-11-02T10:00:00.000Z").epochSecond
        val inputJson = """
        {
            "unit_id": "calamp-unit-002",
            "vid": "calamp-vehicle-888",
            "msg_ts": $inputTimestampSeconds,
            "gps_lat": 37.0000,
            "gps_lon": -116.0000
        }
        """.trimIndent() // speed, fuel, voltage, specificValue missing

        val resultJson = translator.process(inputJson, mockContext)
        assertNotNull(resultJson)
        val resultCmf = objectMapper.readValue(resultJson, CommonMessageFormat::class.java)

        assertEquals("calamp-vehicle-888", resultCmf.vehicleId)
        assertNotNull(resultCmf.telemetry)
        val telemetry = resultCmf.telemetry!!
        assertNull(telemetry.speedGpsMph)
        assertNull(telemetry.fuelLevelPct)
        assertNull(telemetry.batteryVoltage)

        @Suppress("UNCHECKED_CAST")
        val sourceSpecific = resultCmf.sourceSpecificData as Map<String, Any>
        assertTrue(sourceSpecific.isEmpty())
    }

    @Test
    fun `process malformed CalAmp input should return null and log error`() {
        val inputJson = """{"unit_id": "test"}""" // Missing vid, msg_ts, lat, lon

        val resultJson = translator.process(inputJson, mockContext)
        assertNull(resultJson)
        Mockito.verify(mockLogger).error(Mockito.contains("Failed to process CalAmp input"), Mockito.anyString(), Mockito.anyString(), Mockito.any(Exception::class.java))
    }
}
