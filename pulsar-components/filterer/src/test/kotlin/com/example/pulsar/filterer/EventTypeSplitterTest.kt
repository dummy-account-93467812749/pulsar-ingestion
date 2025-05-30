package com.example.pulsar.filterer

import com.example.pulsar.libs.CommonEvent
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.apache.pulsar.client.api.Schema
import org.apache.pulsar.client.api.TypedMessageBuilder
import org.apache.pulsar.functions.api.Context
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.slf4j.Logger
import java.util.UUID
import kotlin.test.assertNull

class EventTypeSplitterTest {

    private lateinit var splitter: EventTypeSplitter
    private lateinit var mockContext: Context
    private lateinit var mockLogger: Logger
    private lateinit var mockTypedMessageBuilder: TypedMessageBuilder<ByteArray>
    private val objectMapper = jacksonObjectMapper()

    @BeforeEach
    fun setUp() {
        splitter = EventTypeSplitter()
        mockContext = mock()
        mockLogger = mock()
        mockTypedMessageBuilder = mock()

        whenever(mockContext.logger).thenReturn(mockLogger)
        whenever(mockContext.newOutputMessage(any<String>(), eq(Schema.BYTES)))
            .thenReturn(mockTypedMessageBuilder)
        whenever(mockTypedMessageBuilder.value(any<ByteArray>()))
            .thenReturn(mockTypedMessageBuilder)
    }

    private fun createCommonEventJson(eventType: String, data: String = "{}"): String {
        val event = CommonEvent(
            eventId = UUID.randomUUID().toString(),
            source = "test-source",
            eventType = eventType,
            timestamp = "2023-01-01T00:00:00Z",
            data = objectMapper.readTree(data),
        )
        return objectMapper.writeValueAsString(event)
    }

    @Test
    fun `process valid event routes to correct topic`() {
        val eventType = "TEST_EVENT"
        val inputJson = createCommonEventJson(eventType)
        val expectedTopic = "persistent://public/default/fn-split-test-event"
        val expectedPayload = inputJson.toByteArray()

        splitter.process(inputJson, mockContext)

        verify(mockLogger).info(
            eq("Successfully deserialized eventId: {}, eventType: {}"),
            any<String>(),
            eq(eventType),
        )

        verify(mockContext).newOutputMessage(eq(expectedTopic), eq(Schema.BYTES))
        verify(mockTypedMessageBuilder).value(eq(expectedPayload))
        verify(mockTypedMessageBuilder).sendAsync()

        verify(mockLogger).info(
            eq("Routed eventId: {} (type: {}) to topic: {}"),
            any<String>(),
            eq(eventType),
            eq(expectedTopic),
        )
    }

    @Test
    fun `process event with eventType needing sanitization routes to sanitized topic`() {
        val eventType = "User Profile Update"
        val inputJson = createCommonEventJson(eventType)
        val expectedTopic = "persistent://public/default/fn-split-user-profile-update"
        val expectedPayload = inputJson.toByteArray()

        splitter.process(inputJson, mockContext)

        verify(mockContext).newOutputMessage(eq(expectedTopic), eq(Schema.BYTES))
        verify(mockTypedMessageBuilder).value(eq(expectedPayload))
        verify(mockTypedMessageBuilder).sendAsync()

        verify(mockLogger).info(
            eq("Routed eventId: {} (type: {}) to topic: {}"),
            any<String>(),
            eq(eventType),
            eq(expectedTopic),
        )
    }

    @Test
    fun `process event with complex eventType needing sanitization`() {
        val eventType = "ORDER_CREATED!@#V2"
        val inputJson = createCommonEventJson(eventType)
        val expectedTopic = "persistent://public/default/fn-split-order-created---v2"
        val expectedPayload = inputJson.toByteArray()

        splitter.process(inputJson, mockContext)

        verify(mockContext).newOutputMessage(eq(expectedTopic), eq(Schema.BYTES))
        verify(mockTypedMessageBuilder).value(eq(expectedPayload))
        verify(mockTypedMessageBuilder).sendAsync()

        verify(mockLogger).info(
            eq("Routed eventId: {} (type: {}) to topic: {}"),
            any<String>(),
            eq(eventType),
            eq(expectedTopic),
        )
    }

    @Test
    fun `process malformed JSON logs error and does not route`() {
        val malformedJson = "{ \"eventId\": \"123\", \"eventType\": \"BAD_JSON\""

        splitter.process(malformedJson, mockContext)

        verify(mockLogger).error(
            eq("Failed to deserialize input JSON to CommonEvent: {}. Error: {}"),
            eq(malformedJson),
            any<String>(),
            any<Throwable>(),
        )

        verify(mockContext, never()).newOutputMessage(any<String>(), eq(Schema.BYTES))
        verify(mockTypedMessageBuilder, never()).value(any<ByteArray>())
        verify(mockTypedMessageBuilder, never()).sendAsync()
    }

    @Test
    fun `process null input logs warning and does not route`() {
        splitter.process(null, mockContext)

        verify(mockLogger).warn(eq("Received null input. Skipping."))
        verify(mockContext, never()).newOutputMessage(any<String>(), eq(Schema.BYTES))
        verify(mockTypedMessageBuilder, never()).value(any<ByteArray>())
        verify(mockTypedMessageBuilder, never()).sendAsync()
    }

    @Test
    fun `process with null context logs error and returns null`() {
        val result = splitter.process(createCommonEventJson("ANY_EVENT"), null)
        assertNull(result, "Function should return null when context is null")
    }

    @Test
    fun `process logs error if sendAsync fails`() {
        val eventType = "FAIL_SEND_EVENT"
        val inputJson = createCommonEventJson(eventType)
        val expectedTopic = "persistent://public/default/fn-split-fail-send-event"
        val exceptionMessage = "Pulsar client failed to send"
        val expectedPayload = inputJson.toByteArray()

        whenever(mockTypedMessageBuilder.sendAsync())
            .thenThrow(RuntimeException(exceptionMessage))

        splitter.process(inputJson, mockContext)

        verify(mockContext).newOutputMessage(eq(expectedTopic), eq(Schema.BYTES))
        verify(mockTypedMessageBuilder).value(eq(expectedPayload))
        verify(mockTypedMessageBuilder).sendAsync()

        // Verify error log with throwable
        verify(mockLogger).error(
            eq("Failed to send message for eventId: {} (type: {}) to topic: {}. Error: {}"),
            any<String>(),
            eq(eventType),
            eq(expectedTopic),
            eq(exceptionMessage),
            any<Throwable>(),
        )
    }
}
