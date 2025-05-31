package com.example.pulsar.filterer

import com.example.pulsar.common.CommonMessageFormat // Assuming this is the correct import
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.apache.pulsar.client.api.MessageId
import org.apache.pulsar.client.api.Schema
import org.apache.pulsar.client.api.TypedMessageBuilder
import org.apache.pulsar.functions.api.Context
import org.apache.pulsar.functions.api.Record
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.util.concurrent.CompletableFuture

@ExtendWith(MockitoExtension::class)
class FiltererTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockRecord: Record<String>

    @Mock
    private lateinit var mockMessageId: MessageId

    @Mock
    private lateinit var mockTypedMessageBuilder: TypedMessageBuilder<String>

    @Captor
    private lateinit var topicCaptor: ArgumentCaptor<String>

    @Captor
    private lateinit var messageCaptor: ArgumentCaptor<String>

    private lateinit var filterer: Filterer

    private val objectMapper = jacksonObjectMapper() // For creating test JSON

    companion object {
        private const val TEST_MESSAGE_ID = "test-msg-id-123"
    }

    @BeforeEach
    fun setUp() {
        filterer = Filterer()

        // Mock basic context interactions
        lenient().`when`(mockContext.currentRecord).thenReturn(mockRecord)
        lenient().`when`(mockRecord.messageId).thenReturn(mockMessageId)
        lenient().`when`(mockMessageId.toString()).thenReturn(TEST_MESSAGE_ID)

        // Mock output message builder chain
        lenient().`when`(mockContext.newOutputMessage(anyOrNull(), eq(Schema.STRING))).thenReturn(mockTypedMessageBuilder)
        lenient().`when`(mockTypedMessageBuilder.value(anyOrNull<String>())).thenReturn(mockTypedMessageBuilder)
        lenient().`when`(mockTypedMessageBuilder.sendAsync()).thenReturn(CompletableFuture.completedFuture(null))
    }

    private fun createCmfJson(
        tenantId: String?,
        includeMetaField: Boolean = true,
        includeTenantIdField: Boolean = true,
        deviceId: String = "testDevice",
        vehicleId: String = "testVehicle"
    ): String {
        val metaNode = if (includeMetaField) {
            if (includeTenantIdField) {
                mapOf("tenantId" to tenantId)
            } else {
                // Meta field present, but tenantId field itself is missing
                emptyMap<String, String?>()
            }
        } else {
            // Meta field itself is absent
            null
        }

        val cmf = CommonMessageFormat(
            dateTime = "2023-01-01T12:00:00Z",
            epochSource = 1672574400L,
            vehicleId = vehicleId,
            deviceId = deviceId,
            sourceType = com.example.pulsar.common.SourceType.Geotab, // Example sourceType
            partitionKey = vehicleId,
            telemetry = null, // Not relevant for this filterer's logic directly
            events = null,    // Not relevant
            sourceSpecificData = objectMapper.createObjectNode() as JsonNode, // Dummy JsonNode
            meta = metaNode?.let { com.example.pulsar.common.CommonMeta(it) }
        )
        return objectMapper.writeValueAsString(cmf)
    }


    @Test
    fun `process should route message when tenantId is present`() {
        val tenantId = "acme-corp"
        val inputJson = createCmfJson(tenantId = tenantId)
        val expectedTopic = "persistent://$tenantId/integration/telemetry"

        filterer.process(inputJson, mockContext)

        verify(mockContext).newOutputMessage(topicCaptor.capture(), eq(Schema.STRING))
        verify(mockTypedMessageBuilder).value(messageCaptor.capture())
        verify(mockTypedMessageBuilder).sendAsync()

        assert(topicCaptor.value == expectedTopic)
        assert(messageCaptor.value == inputJson) // Verify original message is published
    }

    @Test
    fun `process should not route when tenantId is null`() {
        val inputJson = createCmfJson(tenantId = null, includeMetaField = true, includeTenantIdField = true)

        filterer.process(inputJson, mockContext)

        verify(mockContext, never()).newOutputMessage(anyOrNull(), anyOrNull())
    }

    @Test
    fun `process should not route when tenantId is blank`() {
        val inputJson = createCmfJson(tenantId = "  ", includeMetaField = true, includeTenantIdField = true)

        filterer.process(inputJson, mockContext)

        verify(mockContext, never()).newOutputMessage(anyOrNull(), anyOrNull())
    }

    @Test
    fun `process should not route when tenantId field is missing from meta`() {
        val inputJson = createCmfJson(tenantId = null, includeMetaField = true, includeTenantIdField = false)
        // This JSON will look like: { ..., "meta": {} }

        filterer.process(inputJson, mockContext)

        verify(mockContext, never()).newOutputMessage(anyOrNull(), anyOrNull())
    }


    @Test
    fun `process should not route when meta field is missing`() {
        val inputJson = createCmfJson(tenantId = "any-tenant", includeMetaField = false)
        // This JSON will look like: { ..., "meta": null } or meta field absent

        filterer.process(inputJson, mockContext)

        verify(mockContext, never()).newOutputMessage(anyOrNull(), anyOrNull())
    }

    @Test
    fun `process should handle malformed JSON input gracefully`() {
        val malformedJson = "{ \"dateTime\": \"2023-01-01T12:00:00Z\", \"epochSource\": 1672574400, MetaUnclosed: {}" // Malformed

        filterer.process(malformedJson, mockContext)

        verify(mockContext, never()).newOutputMessage(anyOrNull(), anyOrNull())
        // Logger interaction for error could be verified if logger was injected and mockable
    }

    @Test
    fun `process should handle sendAsync failure`() {
        val tenantId = "fail-corp"
        val inputJson = createCmfJson(tenantId = tenantId)
        val failedFuture = CompletableFuture<MessageId>()
        failedFuture.completeExceptionally(RuntimeException("Simulated send failure"))

        `when`(mockTypedMessageBuilder.sendAsync()).thenReturn(failedFuture)

        filterer.process(inputJson, mockContext)

        verify(mockContext).newOutputMessage(anyString(), eq(Schema.STRING))
        verify(mockTypedMessageBuilder).value(inputJson)
        verify(mockTypedMessageBuilder).sendAsync() // verify it was called
        // Further verification could involve checking logs if the logger was mockable/injectable
        // For now, ensuring it doesn't crash and attempts the send is key.
    }

    @Test
    fun `process should handle null input string gracefully`() {
        // The Filterer class itself handles null input by logging and returning.
        // This test is more about ensuring no NPEs occur if process is somehow called with null.
        // The `Function<String, Unit>` implies input is non-null, but defensive checks are good.
        // However, the current Filterer code has a null check for input and returns early.
        // So, we expect no interaction with output message building.
        filterer.process(null as String?, mockContext) // Casting to String? to satisfy the signature if needed by a test framework

        verify(mockContext, never()).newOutputMessage(anyOrNull(), anyOrNull())
    }
}
