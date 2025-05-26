package com.example.pulsar.functions.transforms.translators

import com.example.pulsar.common.CommonEvent
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.*
import org.apache.pulsar.functions.api.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class ShipmentStatusTranslatorTest {

    private lateinit var translator: ShipmentStatusTranslator
    private lateinit var mockContext: Context
    private lateinit var mockLogger: Logger
    private val objectMapper = jacksonObjectMapper()
    private val isoFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        mockContext = mockk()
        mockLogger = mockk(relaxed = true)
        translator = ShipmentStatusTranslator()
        every { mockContext.logger } returns mockLogger
    }

    @Test
    fun `process valid ShipmentStatus JSON should return CommonEvent JSON`() {
        val shipId = "SHIP-321"
        val status = "DELIVERED"
        val deliveredAtEpoch = 1620100000L

        // Corrected JSON string with escaped quotes
        val inputJsonString = "{" +
            "\"shipId\": \"$shipId\"," +
            "\"status\": \"$status\"," +
            "\"deliveredAt\": $deliveredAtEpoch" +
            "}"

        val expectedTimestamp = Instant.ofEpochSecond(deliveredAtEpoch).atOffset(ZoneOffset.UTC).format(isoFormatter)
        val result = translator.process(inputJsonString, mockContext)

        assertNotNull(result)
        val commonEventResult = objectMapper.readValue(result, CommonEvent::class.java)

        assertEquals("shipping-service", commonEventResult.source)
        assertEquals("SHIPMENT_EVENT", commonEventResult.eventType)
        assertEquals(expectedTimestamp, commonEventResult.timestamp)
        assertNotNull(commonEventResult.eventId)
        assertTrue(commonEventResult.eventId.isNotBlank())

        val dataNode = commonEventResult.data
        assertEquals(shipId, dataNode.get("shipId").asText())
        assertEquals(status, dataNode.get("status").asText())
        assertEquals(deliveredAtEpoch, dataNode.get("deliveredAt").asLong())
        
        verify { mockLogger.info(any<String>(), shipId, commonEventResult.eventId) }
    }

    @Test
    fun `process malformed JSON should return null and log error`() {
        // Corrected JSON string with escaped quotes
        val malformedJson = "{ \"shipId\": \"SHIP-123\", "
        val result = translator.process(malformedJson, mockContext)
        assertNull(result)
        verify { mockLogger.error(any<String>(), malformedJson, any<String>(), any<Exception>()) }
    }

    @Test
    fun `process JSON missing required shipId field should return null and log error`() {
        // Corrected JSON string with escaped quotes
        val jsonMissingField = "{" +
            "\"status\": \"DELIVERED\"," +
            "\"deliveredAt\": 1620100000" +
            "}"
        val result = translator.process(jsonMissingField, mockContext)
        assertNull(result)
        verify { mockLogger.error("Missing required fields 'shipId' or 'deliveredAt' in input: {}", jsonMissingField) }
    }

    @Test
    fun `process JSON missing required deliveredAt field should return null and log error`() {
        // Corrected JSON string with escaped quotes
        val jsonMissingField = "{" +
            "\"shipId\": \"SHIP-XYZ\"," +
            "\"status\": \"IN_TRANSIT\"" +
            "}"
        val result = translator.process(jsonMissingField, mockContext)
        assertNull(result)
        verify { mockLogger.error("Missing required fields 'shipId' or 'deliveredAt' in input: {}", jsonMissingField) }
    }

    @Test
    fun `process null input should return null and log warning`() {
        val result = translator.process(null, mockContext)
        assertNull(result)
        verify { mockLogger.warn("Received null input. Skipping.") }
    }
}
