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

class FordTranslatorTest {

    private lateinit var translator: FordTranslator
    private lateinit var mockContext: Context
    private lateinit var mockLogger: Logger
    private val objectMapper = jacksonObjectMapper()

    @BeforeEach
    fun setUp() {
        translator = FordTranslator()
        mockContext = Mockito.mock(Context::class.java)
        mockLogger = Mockito.mock(Logger::class.java)
        Mockito.`when`(mockContext.logger).thenReturn(mockLogger)
        Mockito.`when`(mockContext.tenant).thenReturn("ford-tenant")
    }

    @Test
    fun `process valid Ford input should return correct CMF JSON`() {
        val captureTimeMillis = Instant.parse("2023-12-01T12:00:00.000Z").toEpochMilli()
        val locationTimeMillis = Instant.parse("2023-12-01T11:59:58.000Z").toEpochMilli()

        val inputJson = """
        {
            "vin": "ford-vin-111",
            "esn": "ford-esn-222",
            "captureTime": $captureTimeMillis,
            "coords": {
                "latValue": 40.7128,
                "lonValue": -74.0060,
                "ts": $locationTimeMillis
            },
            "vehicleSpeed": 70.2,
            "fuelRemainingGallons": 10.5,
            "rpm": 3000,
            "fordExtraData": {
                "doorStatus": "all_closed",
                "tirePressureStatus": "normal"
            }
        }
        """.trimIndent()

        val resultJson = translator.process(inputJson, mockContext)
        assertNotNull(resultJson)

        val resultCmf = objectMapper.readValue(resultJson, CommonMessageFormat::class.java)

        assertEquals("ford-vin-111", resultCmf.vehicleId)
        assertEquals("ford-esn-222", resultCmf.deviceId)
        assertEquals(SourceType.Ford, resultCmf.sourceType)
        assertEquals(captureTimeMillis, resultCmf.epochSource)
        assertTrue(resultCmf.dateTime.startsWith("2023-12-01T12:00:00"))
        assertEquals("ford-tenant", resultCmf.tenantId)
        assertEquals("ford-vin-111", resultCmf.partitionKey)

        assertNotNull(resultCmf.telemetry)
        val telemetry = resultCmf.telemetry!!
        assertEquals(40.7128, telemetry.location?.lat)
        assertEquals(-74.0060, telemetry.location?.lon)
        assertTrue(telemetry.location?.timestamp!!.startsWith("2023-12-01T11:59:58"))
        assertEquals(70.2, telemetry.speedGpsMph)
        assertEquals(10.5, telemetry.fuelLevelGallon)
        assertEquals(3000.0, telemetry.engineRpm)

        assertNotNull(resultCmf.sourceSpecificData)
        @Suppress("UNCHECKED_CAST")
        val sourceSpecific = resultCmf.sourceSpecificData as Map<String, Any>
        assertEquals("all_closed", sourceSpecific["doorStatus"])
        assertEquals("normal", sourceSpecific["tirePressureStatus"])

        Mockito.verify(mockLogger).info(Mockito.contains("Successfully transformed Ford message"), Mockito.anyString(), Mockito.anyLong())
    }

    @Test
    fun `process Ford input with missing optional fields should succeed`() {
        val captureTimeMillis = Instant.parse("2023-12-02T14:30:00.000Z").toEpochMilli()
        val locationTimeMillis = Instant.parse("2023-12-02T14:29:55.000Z").toEpochMilli()

        val inputJson = """
        {
            "vin": "ford-vin-333",
            "esn": "ford-esn-444",
            "captureTime": $captureTimeMillis,
            "coords": {
                "latValue": 41.0000,
                "lonValue": -75.0000,
                "ts": $locationTimeMillis
            }
        }
        """.trimIndent() // speed, fuel, rpm, extraData missing

        val resultJson = translator.process(inputJson, mockContext)
        assertNotNull(resultJson)
        val resultCmf = objectMapper.readValue(resultJson, CommonMessageFormat::class.java)

        assertEquals("ford-vin-333", resultCmf.vehicleId)
        assertNotNull(resultCmf.telemetry)
        val telemetry = resultCmf.telemetry!!
        assertNull(telemetry.speedGpsMph)
        assertNull(telemetry.fuelLevelGallon)
        assertNull(telemetry.engineRpm)

        @Suppress("UNCHECKED_CAST")
        val sourceSpecific = resultCmf.sourceSpecificData as Map<String, Any>
        assertTrue(sourceSpecific.isEmpty())
    }

    @Test
    fun `process malformed Ford input should return null and log error`() {
        val inputJson = """{"vin": "test"}""" // Missing esn, captureTime, coords

        val resultJson = translator.process(inputJson, mockContext)
        assertNull(resultJson)
        Mockito.verify(mockLogger).error(Mockito.contains("Failed to process Ford input"), Mockito.anyString(), Mockito.anyString(), Mockito.any(Exception::class.java))
    }
}
