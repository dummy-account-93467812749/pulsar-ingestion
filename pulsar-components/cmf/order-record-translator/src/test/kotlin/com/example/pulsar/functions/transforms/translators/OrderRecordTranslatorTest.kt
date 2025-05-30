package com.example.pulsar.functions.cmf

import com.example.pulsar.libs.CommonEvent
import com.fasterxml.jackson.databind.node.JsonNodeFactory
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

class OrderRecordTranslatorTest {

    private lateinit var translator: OrderRecordTranslator
    private lateinit var mockContext: Context
    private lateinit var mockLogger: Logger
    private val objectMapper = jacksonObjectMapper()

    @BeforeEach
    fun setUp() {
        mockContext = mockk()
        mockLogger = mockk(relaxed = true) // relaxed = true means stubs return default values
        translator = OrderRecordTranslator()
        every { mockContext.logger } returns mockLogger
    }

    @Test
    fun `process valid OrderRecord JSON should return CommonEvent JSON`() {
        val orderId = "ORD-456"
        val placedAt = "2025-05-26T10:00:00Z"
        // Using JsonNodeFactory for items array to ensure it's valid JSON structure
        val itemsArray = JsonNodeFactory.instance.arrayNode().add("item1").add("item2")

        val inputJsonString = "{" +
            "\"orderId\": \"$orderId\"," +
            "\"items\": $itemsArray," + // Serialize itemsArray to string
            "\"placedAt\": \"$placedAt\"" +
            "}"

        val result = translator.process(inputJsonString, mockContext)

        assertNotNull(result)
        val commonEventResult = objectMapper.readValue(result, CommonEvent::class.java)

        assertEquals("order-service", commonEventResult.source)
        assertEquals("ORDER_EVENT", commonEventResult.eventType)
        assertEquals(placedAt, commonEventResult.timestamp) // Timestamp is passed as is
        assertNotNull(commonEventResult.eventId)
        assertTrue(commonEventResult.eventId.isNotBlank())

        val dataNode = commonEventResult.data
        assertEquals(orderId, dataNode.get("orderId").asText())
        assertEquals(itemsArray, dataNode.get("items")) // Compare JsonNode directly
        assertEquals(placedAt, dataNode.get("placedAt").asText())

        // Less strict: Check that info is logged with any message format,
        // an argument equal to orderId, and any other string argument (for eventId).
        verify { mockLogger.info(any<String>(), eq(orderId), any<String>()) }
    }

    @Test
    fun `process malformed JSON should return null and log error`() {
        val malformedJson = "{ \"orderId\": \"ORD-123\", " // Intentionally malformed
        val result = translator.process(malformedJson, mockContext)
        assertNull(result)

        // Less strict: Check that error is logged with any message format,
        // any first object argument, any second object argument, and any throwable.
        // This assumes the original log call had a format string, two regular arguments, and a throwable.
        verify { mockLogger.error(any<String>(), any(), any(), any<Throwable>()) }
    }

    @Test
    fun `process JSON missing required orderId field should return null and log error`() {
        val itemsArray = JsonNodeFactory.instance.arrayNode().add("item1")
        val jsonMissingField = "{" +
            "\"items\": $itemsArray," +
            "\"placedAt\": \"2025-05-26T10:00:00Z\"" +
            "}"
        val result = translator.process(jsonMissingField, mockContext)
        assertNull(result)

        // Less strict: Check that error is logged with any message format,
        // and an argument equal to jsonMissingField.
        verify { mockLogger.error(any<String>(), eq(jsonMissingField)) }
    }

    @Test
    fun `process JSON missing required placedAt field should return null and log error`() {
        val itemsArray = JsonNodeFactory.instance.arrayNode().add("item1")
        val jsonMissingField = "{" +
            "\"orderId\": \"ORD-789\"," +
            "\"items\": $itemsArray" +
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
