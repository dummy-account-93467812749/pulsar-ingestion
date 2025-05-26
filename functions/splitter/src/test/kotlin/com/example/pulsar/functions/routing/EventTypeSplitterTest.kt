package com.example.pulsar.functions.routing

import com.example.pulsar.common.CommonEvent
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.apache.pulsar.client.api.MessageBuilder // Not strictly needed for these tests but good for context
import org.apache.pulsar.client.api.TypedMessageBuilder
import org.apache.pulsar.functions.api.Context
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.slf4j.Logger
import java.util.UUID

class EventTypeSplitterTest {

    private lateinit var splitter: EventTypeSplitter
    private lateinit var mockContext: Context
    private lateinit var mockLogger: Logger
    private lateinit var mockTypedMessageBuilder: TypedMessageBuilder<ByteArray>

    private val objectMapper = jacksonObjectMapper()

    @BeforeEach
    fun setUp() {
        splitter = EventTypeSplitter()
        mockContext = mock<Context>()
        mockLogger = mock<Logger>()
        mockTypedMessageBuilder = mock<TypedMessageBuilder<ByteArray>>()

        // Standard mocking for context and logger
        whenever(mockContext.logger).thenReturn(mockLogger)
        // Mock newOutputMessage to return our mock TypedMessageBuilder
        // The actual function call in EventTypeSplitter is context.newOutputMessage(targetTopic, byte[])
        // so we need to match against (String, ByteArray)
        whenever(mockContext.newOutputMessage(any<String>(), any<ByteArray>())).thenReturn(mockTypedMessageBuilder)
        
        // The EventTypeSplitter directly calls sendAsync on the result of newOutputMessage,
        // it does not use .value() for byte array payloads.
        // So, no need to mock mockTypedMessageBuilder.value(any()) for these tests.
        // If .value() were used, then: whenever(mockTypedMessageBuilder.value(any())).thenReturn(mockTypedMessageBuilder)
    }

    private fun createCommonEventJson(eventType: String, data: String = "{}"): String {
        val event = CommonEvent(
            eventId = UUID.randomUUID().toString(),
            source = "test-source",
            eventType = eventType,
            timestamp = "2023-01-01T00:00:00Z",
            data = objectMapper.readTree(data)
        )
        return objectMapper.writeValueAsString(event)
    }

    @Test
    fun `process valid event routes to correct topic`() {
        val eventType = "TEST_EVENT"
        val inputJson = createCommonEventJson(eventType)
        // Sanitization for "TEST_EVENT" -> "test_event" (lowercase)
        val expectedTopic = "persistent://public/default/fn-split-test_event" 

        splitter.process(inputJson, mockContext)

        verify(mockLogger).info(contains("Successfully deserialized eventId"))
        // Verify newOutputMessage with the expected topic and the inputJson converted to ByteArray
        verify(mockContext).newOutputMessage(eq(expectedTopic), eq(inputJson.toByteArray()))
        verify(mockTypedMessageBuilder).sendAsync()
        verify(mockLogger).info(contains("Routed eventId:"), contains(eventType), contains(expectedTopic))
    }

    @Test
    fun `process event with eventType needing sanitization routes to sanitized topic`() {
        val eventType = "User Profile Update"
        val inputJson = createCommonEventJson(eventType)
        // Sanitization: lowercase, spaces to hyphens
        val expectedTopic = "persistent://public/default/fn-split-user-profile-update" 

        splitter.process(inputJson, mockContext)

        verify(mockContext).newOutputMessage(eq(expectedTopic), eq(inputJson.toByteArray()))
        verify(mockTypedMessageBuilder).sendAsync()
        verify(mockLogger).info(contains("Routed eventId:"), contains(eventType), contains(expectedTopic))
    }
    
    @Test
    fun `process event with complex eventType needing sanitization`() {
        val eventType = "ORDER_CREATED!@#V2"
        val inputJson = createCommonEventJson(eventType)
        // Sanitization: lowercase, replace non-alphanumeric (except hyphen) with hyphens
        // "order_created!@#v2" -> "order_created---v2" (assuming !@# become -)
        val expectedTopic = "persistent://public/default/fn-split-order_created---v2"

        splitter.process(inputJson, mockContext)

        verify(mockContext).newOutputMessage(eq(expectedTopic), eq(inputJson.toByteArray()))
        verify(mockTypedMessageBuilder).sendAsync()
        verify(mockLogger).info(contains("Routed eventId:"), contains(eventType), contains(expectedTopic))
    }

    @Test
    fun `process malformed JSON logs error and does not route`() {
        val malformedJson = "{ \"eventId\": \"123\", \"eventType\": \"BAD_JSON\"" // Missing closing brace

        splitter.process(malformedJson, mockContext)

        verify(mockLogger).error(contains("Failed to deserialize input JSON to CommonEvent:"), eq(malformedJson), anyOrNull(), anyOrNull())
        verify(mockContext, never()).newOutputMessage(any<String>(), any<ByteArray>())
        verify(mockTypedMessageBuilder, never()).sendAsync()
    }

    @Test
    fun `process null input logs warning and does not route`() {
        splitter.process(null, mockContext)

        verify(mockLogger).warn("Received null input. Skipping.")
        verify(mockContext, never()).newOutputMessage(any<String>(), any<ByteArray>())
        verify(mockTypedMessageBuilder, never()).sendAsync()
    }
    
    @Test
    fun `process with null context logs error and returns null`() {
        // The implementation uses println for this specific scenario before logger is set.
        // We primarily test that it returns null and doesn't throw an NPE.
        val result = splitter.process(createCommonEventJson("ANY_EVENT"), null)
        kotlin.test.assertNull(result, "Function should return null when context is null")
        
        // We cannot verify mockLogger interactions as it would not be initialized.
        // Verifying println output is outside typical Mockito/JUnit scope without extra setup.
    }

    @Test
    fun `process logs error if sendAsync fails`() {
        val eventType = "FAIL_SEND_EVENT"
        val inputJson = createCommonEventJson(eventType)
        val expectedTopic = "persistent://public/default/fn-split-fail_send_event"
        val exceptionMessage = "Pulsar client failed to send"
        
        whenever(mockTypedMessageBuilder.sendAsync()).thenThrow(RuntimeException(exceptionMessage))

        splitter.process(inputJson, mockContext)
        
        verify(mockContext).newOutputMessage(eq(expectedTopic), eq(inputJson.toByteArray()))
        verify(mockTypedMessageBuilder).sendAsync()
        verify(mockLogger).error(
            contains("Failed to send message for eventId:"),
            // Use argThat or more flexible matchers if specific parts of eventId/eventType are needed
            // For now, checking for key parts of the log message:
            contains(eventType),
            contains(expectedTopic),
            contains(exceptionMessage), 
            anyOrNull() // For the throwable itself
        )
    }
}
