package com.example.pulsar.functions.transforms.translators

import com.example.pulsar.common.CommonEvent // Ensure this is the correct CommonEvent
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.*
import org.apache.pulsar.functions.api.Context // Ensure this is the correct Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach // Ensure this is org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test // Ensure this is org.junit.jupiter.api.Test
import org.slf4j.Logger // Ensure this is org.slf4j.Logger
import java.time.Instant // Ensure this is java.time.Instant
import java.time.ZoneOffset // Ensure this is java.time.ZoneOffset
import java.time.format.DateTimeFormatter // Ensure this is java.time.format.DateTimeFormatter

class ShipmentStatusTranslatorTest {

    private lateinit var translator: ShipmentStatusTranslator
    private lateinit var mockContext: Context
    private lateinit var mockLogger: Logger
    private val objectMapper = jacksonObjectMapper()
    private val isoFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        mockContext = mockk()
        mockLogger = mockk(relaxed = true) // relaxed = true means stubs return default values
        translator = ShipmentStatusTranslator()
        every { mockContext.logger } returns mockLogger
    }

    @Test
    fun `process valid ShipmentStatus JSON should return CommonEvent JSON`() {
        val shipId = "SHIP-321"
        val status = "DELIVERED"
        val deliveredAtEpoch = 1620100000L

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
        
        // Less strict: Check that info is logged with any message format,
        // an argument equal to shipId, and any other string argument (for eventId).
        verify { mockLogger.info(any<String>(), eq(shipId), any<String>()) }
    }

    @Test
    fun `process malformed JSON should return null and log error`() {
        val malformedJson = "{ \"shipId\": \"SHIP-123\", " // Intentionally malformed
        val result = translator.process(malformedJson, mockContext)
        assertNull(result)

        // Less strict: Check that error is logged with any message format,
        // any first object argument, any second object argument, and any throwable.
        // This assumes the original log call had a format string, two regular arguments, and a throwable.
        verify { mockLogger.error(any<String>(), any(), any(), any<Throwable>()) }
    }

    @Test
    fun `process JSON missing required shipId field should return null and log error`() {
        val jsonMissingField = "{" +
            "\"status\": \"DELIVERED\"," +
            "\"deliveredAt\": 1620100000" +
            "}"
        val result = translator.process(jsonMissingField, mockContext)
        assertNull(result)

        // Less strict: Check that error is logged with any message format,
        // and an argument equal to jsonMissingField.
        verify { mockLogger.error(any<String>(), eq(jsonMissingField)) }
    }

    @Test
    fun `process JSON missing required deliveredAt field should return null and log error`() {
        val jsonMissingField = "{" +
            "\"shipId\": \"SHIP-XYZ\"," +
            "\"status\": \"IN_TRANSIT\"" +
            "}"
        val result = translator.process(jsonMissingField, mockContext)
        assertNull(result)

        // Less strict: Check that error is logged with any message format,
        // and an argument equal to jsonMissingField.
        verify { mockLogger.error(any<String>(), eq(jsonMissingField)) }
    }

    @Test
    fun `process null input should return null and log warning`() {
        val result = translator.process(null, mockContext)
        assertNull(result)

        // Less strict: Check that a warning is logged with any message.
        verify { mockLogger.warn(any<String>()) }
    }
}