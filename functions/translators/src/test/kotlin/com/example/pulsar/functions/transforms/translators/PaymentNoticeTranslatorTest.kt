package com.example.pulsar.functions.transforms.translators

import com.example.pulsar.common.CommonEvent
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.*
import org.apache.pulsar.functions.api.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.Logger

class PaymentNoticeTranslatorTest {

    private lateinit var translator: PaymentNoticeTranslator
    private lateinit var mockContext: Context
    private lateinit var mockLogger: Logger
    private val objectMapper = jacksonObjectMapper()

    @BeforeEach
    fun setUp() {
        mockContext = mockk()
        mockLogger = mockk(relaxed = true) // relaxed = true means stubs return default values
        translator = PaymentNoticeTranslator()
        every { mockContext.logger } returns mockLogger
    }

    @Test
    fun `process valid PaymentNotice JSON should return CommonEvent JSON`() {
        val txnId = "TXN-001"
        val amount = 99.95
        val currency = "USD"
        val time = "2025-05-26T09:30:00Z"

        val inputJsonString = "{" +
            "\"txnId\": \"$txnId\"," +
            "\"amount\": $amount," +
            "\"currency\": \"$currency\"," +
            "\"time\": \"$time\"" +
            "}"

        val result = translator.process(inputJsonString, mockContext)

        assertNotNull(result)
        val commonEventResult = objectMapper.readValue(result, CommonEvent::class.java)

        assertEquals("payment-gateway", commonEventResult.source)
        assertEquals("PAYMENT_EVENT", commonEventResult.eventType)
        assertEquals(time, commonEventResult.timestamp) // Timestamp is passed as is
        assertNotNull(commonEventResult.eventId)
        assertTrue(commonEventResult.eventId.isNotBlank())

        val dataNode = commonEventResult.data
        assertEquals(txnId, dataNode.get("txnId").asText())
        assertEquals(amount, dataNode.get("amount").asDouble())
        assertEquals(currency, dataNode.get("currency").asText())
        assertEquals(time, dataNode.get("time").asText())
        
        // Less strict: Check that info is logged with any message format,
        // an argument equal to txnId, and any other string argument (for eventId).
        verify { mockLogger.info(any<String>(), eq(txnId), any<String>()) }
    }

    @Test
    fun `process malformed JSON should return null and log error`() {
        val malformedJson = "{ \"txnId\": \"TXN-123\", " // Intentionally malformed
        val result = translator.process(malformedJson, mockContext)
        assertNull(result)

        // Less strict: Check that error is logged with any message format,
        // any first object argument, any second object argument, and any throwable.
        // This assumes the original log call had a format string, two regular arguments, and a throwable.
        verify { mockLogger.error(any<String>(), any(), any(), any<Throwable>()) }
    }

    @Test
    fun `process JSON missing required txnId field should return null and log error`() {
        val jsonMissingField = "{" +
            "\"amount\": 99.95," +
            "\"currency\": \"USD\"," +
            "\"time\": \"2025-05-26T09:30:00Z\"" +
            "}"
        val result = translator.process(jsonMissingField, mockContext)
        assertNull(result)

        // Less strict: Check that error is logged with any message format,
        // and an argument equal to jsonMissingField.
        verify { mockLogger.error(any<String>(), eq(jsonMissingField)) }
    }

    @Test
    fun `process JSON missing required time field should return null and log error`() {
        val jsonMissingField = "{" +
            "\"txnId\": \"TXN-001\"," +
            "\"amount\": 99.95," +
            "\"currency\": \"USD\"" +
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