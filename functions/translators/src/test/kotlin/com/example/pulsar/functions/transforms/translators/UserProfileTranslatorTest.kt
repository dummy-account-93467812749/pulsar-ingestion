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

class UserProfileTranslatorTest {

    private lateinit var translator: UserProfileTranslator
    private lateinit var mockContext: Context
    private lateinit var mockLogger: Logger
    private val objectMapper = jacksonObjectMapper()
    private val isoFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        mockContext = mockk()
        mockLogger = mockk(relaxed = true) // relaxed = true to allow for non-essential logging calls
        translator = UserProfileTranslator()
        every { mockContext.logger } returns mockLogger
    }

    @Test
    fun `process valid UserProfile JSON should return CommonEvent JSON`() {
        val uid = 123
        val name = "Alice"
        val createdEpoch = 1620000000L
        
        // Simplified JSON string construction
        val inputJsonString = "{" +
            "\"uid\": " + uid + "," +
            "\"name\": \"" + name + "\"," +
            "\"created\": " + createdEpoch +
            "}"

        val expectedTimestamp = Instant.ofEpochSecond(createdEpoch).atOffset(ZoneOffset.UTC).format(isoFormatter)
        val result = translator.process(inputJsonString, mockContext)

        assertNotNull(result)
        val commonEventResult = objectMapper.readValue(result, CommonEvent::class.java)

        assertEquals("user-service", commonEventResult.source)
        assertEquals("USER_PROFILE_EVENT", commonEventResult.eventType)
        assertEquals(expectedTimestamp, commonEventResult.timestamp)
        assertNotNull(commonEventResult.eventId)
        assertTrue(commonEventResult.eventId.isNotBlank())

        val dataNode = commonEventResult.data
        assertEquals(uid, dataNode.get("uid").asInt())
        assertEquals(name, dataNode.get("name").asText())
        assertEquals(createdEpoch, dataNode.get("created").asLong())
        
        // Verify specific log for successful transformation using any() for the string template
        verify { mockLogger.info(any<String>(), uid.toString(), commonEventResult.eventId) }
    }

    @Test
    fun `process UserProfile with uid as string should also work`() {
        val uid = "user-abc-123"
        val name = "Bob"
        val createdEpoch = 1620000000L
        
        // Simplified JSON string construction
        val inputJsonString = "{" +
            "\"uid\": \"" + uid + "\"," + // uid as string
            "\"name\": \"" + name + "\"," +
            "\"created\": " + createdEpoch +
            "}"

        val result = translator.process(inputJsonString, mockContext)
        assertNotNull(result)
        val commonEventResult = objectMapper.readValue(result, CommonEvent::class.java)
        assertEquals(uid, commonEventResult.data.get("uid").asText())
        // Verify specific log for successful transformation using any() for the string template
        verify { mockLogger.info(any<String>(), uid, commonEventResult.eventId) }
    }
    
    @Test
    fun `process malformed JSON should return null and log error`() {
        val malformedJson = "{ \"uid\": 123, \"name\": \"Alice\", " // Intentionally malformed
        val result = translator.process(malformedJson, mockContext)
        assertNull(result)
        // Verify general error log for parsing failure
        verify { mockLogger.error(any<String>(), malformedJson, any<String>(), any<Exception>()) }
    }

    @Test
    fun `process JSON missing required uid field should return null and log error`() {
        val jsonMissingUid = "{ \"name\": \"Alice\", \"created\": 1620000000 }" // Valid JSON, but missing uid
        val result = translator.process(jsonMissingUid, mockContext)
        assertNull(result)
        verify { mockLogger.error("Missing required fields 'uid' or 'created' in input: {}", jsonMissingUid) }
    }
    
    @Test
    fun `process JSON missing required created field should return null and log error`() {
        val jsonMissingCreated = "{ \"uid\": 123, \"name\": \"Alice\" }" // Valid JSON, but missing created
        val result = translator.process(jsonMissingCreated, mockContext)
        assertNull(result)
        verify { mockLogger.error("Missing required fields 'uid' or 'created' in input: {}", jsonMissingCreated) }
    }

    @Test
    fun `process null input should return null and log warning`() {
        val result = translator.process(null, mockContext)
        assertNull(result)
        verify { mockLogger.warn("Received null input. Skipping.") }
    }
}
