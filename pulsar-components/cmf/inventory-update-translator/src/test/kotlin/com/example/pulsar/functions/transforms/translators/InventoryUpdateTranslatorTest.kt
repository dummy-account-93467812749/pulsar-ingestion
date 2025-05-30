package com.example.pulsar.functions.cmf

import com.example.pulsar.libs.CommonEvent
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.apache.pulsar.functions.api.Context
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
        mockLogger = mockk(relaxed = true) // relaxed = true means stubs return default values
        translator = InventoryUpdateTranslator()
        every { mockContext.logger } returns mockLogger
    }

    @Test
    fun `process valid InventoryUpdate JSON should return CommonEvent JSON`() {
        val sku = "SKU-789"
        val qty = 42
        val updateTimeEpoch = 1620050000L // Example epoch timestamp

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

        // Less strict: Check that info is logged with any message format,
        // an argument equal to sku, and any other string argument (for eventId).
        verify { mockLogger.info(any<String>(), eq(sku), any<String>()) }
    }

    @Test
    fun `process malformed JSON should return null and log error`() {
        val malformedJson = "{ \"sku\": \"SKU-123\", " // Intentionally malformed
        val result = translator.process(malformedJson, mockContext)
        assertNull(result)

        // Less strict: Check that error is logged with any message format,
        // any first object argument, any second object argument, and any throwable.
        // This assumes the original log call had a format string, two regular arguments, and a throwable.
        // If the number of arguments is different, this would need adjustment.
        // Example: logger.error("Format {} {}", arg1, arg2, throwable)
        verify { mockLogger.error(any<String>(), any(), any(), any<Throwable>()) }
    }

    @Test
    fun `process JSON missing required sku field should return null and log error`() {
        val jsonMissingField = "{" +
            "\"qty\": 30," +
            "\"updateTime\": 1620050000" +
            "}"
        val result = translator.process(jsonMissingField, mockContext)
        assertNull(result)

        // Less strict: Check that error is logged with any message format,
        // and an argument equal to jsonMissingField.
        verify { mockLogger.error(any<String>(), eq(jsonMissingField)) }
    }

    @Test
    fun `process JSON missing required updateTime field should return null and log error`() {
        val jsonMissingField = "{" +
            "\"sku\": \"SKU-456\"," +
            "\"qty\": 10" +
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
