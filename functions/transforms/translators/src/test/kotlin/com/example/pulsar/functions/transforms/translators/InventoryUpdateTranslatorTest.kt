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

class InventoryUpdateTranslatorTest {

    private lateinit var translator: InventoryUpdateTranslator
    private lateinit var mockContext: Context
    private lateinit var mockLogger: Logger
    private val objectMapper = jacksonObjectMapper()
    private val isoFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        mockContext = mockk()
        mockLogger = mockk(relaxed = true)
        translator = InventoryUpdateTranslator()
        every { mockContext.logger } returns mockLogger
    }

    @Test
    fun `process valid InventoryUpdate JSON should return CommonEvent JSON`() {
        val sku = "SKU-789"
        val qty = 42
        val updateTimeEpoch = 1620050000L // Example epoch timestamp

        // Note: Corrected JSON string - quotes around keys
        val inputJsonString = "{" +
            "\"sku\": \"$sku\"," +
            "\"qty\": $qty," +
            "\"updateTime\": $updateTimeEpoch" +
            "}"

        val expectedTimestamp = Instant.ofEpochSecond(updateTimeEpoch).atOffset(ZoneOffset.UTC).format(isoFormatter)
        val result = translator.process(inputJsonString, mockContext)

        assertNotNull(result)
        val commonEventResult = objectMapper.readValue(result, CommonEvent::class.java)

        assertEquals("inventory-service", commonEventResult.source)
        assertEquals("INVENTORY_EVENT", commonEventResult.eventType)
        assertEquals(expectedTimestamp, commonEventResult.timestamp)
        assertNotNull(commonEventResult.eventId)
        assertTrue(commonEventResult.eventId.isNotBlank())

        val dataNode = commonEventResult.data
        assertEquals(sku, dataNode.get("sku").asText())
        assertEquals(qty, dataNode.get("qty").asInt())
        assertEquals(updateTimeEpoch, dataNode.get("updateTime").asLong())
        
        verify { mockLogger.info(any<String>(), sku, commonEventResult.eventId) }
    }

    @Test
    fun `process malformed JSON should return null and log error`() {
        // Note: Corrected JSON string - quotes around key
        val malformedJson = "{ \"sku\": \"SKU-123\", "
        val result = translator.process(malformedJson, mockContext)
        assertNull(result)
        verify { mockLogger.error(any<String>(), malformedJson, any<String>(), any<Exception>()) }
    }

    @Test
    fun `process JSON missing required sku field should return null and log error`() {
        // Note: Corrected JSON string - quotes around keys
        val jsonMissingField = "{" +
            "\"qty\": 30," +
            "\"updateTime\": 1620050000" +
            "}"
        val result = translator.process(jsonMissingField, mockContext)
        assertNull(result)
        verify { mockLogger.error("Missing required fields 'sku' or 'updateTime' in input: {}", jsonMissingField) }
    }

    @Test
    fun `process JSON missing required updateTime field should return null and log error`() {
        // Note: Corrected JSON string - quotes around keys
        val jsonMissingField = "{" +
            "\"sku\": \"SKU-456\"," +
            "\"qty\": 10" +
            "}"
        val result = translator.process(jsonMissingField, mockContext)
        assertNull(result)
        verify { mockLogger.error("Missing required fields 'sku' or 'updateTime' in input: {}", jsonMissingField) }
    }

    @Test
    fun `process null input should return null and log warning`() {
        val result = translator.process(null, mockContext)
        assertNull(result)
        verify { mockLogger.warn("Received null input. Skipping.") }
    }
}
