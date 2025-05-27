package org.apache.pulsar.io.http

// Imports for HttpClientEngineConfig and HttpClientEngineFactory will be removed by Spotless if not needed
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders // Already present, ensure it's used if needed by headersOf
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import org.apache.pulsar.functions.api.Record
import org.apache.pulsar.io.core.SourceContext
import org.mockito.Mockito.mock // Keep for mock()
import org.mockito.kotlin.any // Use mockito-kotlin's any
import org.mockito.kotlin.doAnswer
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.AfterMethod
import org.testng.annotations.BeforeMethod
import org.testng.annotations.Test
import java.nio.charset.StandardCharsets
import java.util.Optional
import java.util.concurrent.LinkedBlockingQueue

class HttpSourceTest {

    private lateinit var source: HttpSource
    private lateinit var sourceContext: SourceContext
    private lateinit var mockEngine: MockEngine
    private val queue = LinkedBlockingQueue<Record<ByteArray>>()

    @BeforeMethod
    fun setUp() {
        // source = HttpSource() // Will be initialized in each test with a specific MockEngine
        sourceContext = mock(SourceContext::class.java)
        // Mock the consume method to add records to our queue for inspection
        // This needs to be done on the `source` instance, so it's moved to each test method after source is initialized.
        // For a general setup, if source were initialized here, it would be:
        // doAnswer { invocation ->
        //    val record = invocation.getArgument<Record<ByteArray>>(0)
        //    queue.offer(record)
        //    null
        // }.`when`(source).consume(any(Record::class.java))
    }

    @AfterMethod
    fun tearDown() {
        source.close() // source might not be initialized if a test fails before it
        queue.clear()
    }

    // Removed createMockHttpClient as it's no longer needed; MockEngine is passed to HttpSource constructor

    @Test
    fun testSuccessfulGetRequest() {
        mockEngine = MockEngine { request ->
            assertEquals("http://test.com/data", request.url.toString())
            assertEquals(HttpMethod.Get, request.method)
            respond(
                content = ByteReadChannel("""{"key":"value"}""".toByteArray(StandardCharsets.UTF_8)),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        source = HttpSource(mockEngine) // Initialize HttpSource with the MockEngine
        // Mock the consume method for this specific source instance
        doAnswer { invocation ->
            val record = invocation.getArgument<Record<ByteArray>>(0)
            queue.offer(record)
            null
        }.`when`(source).consume(any<Record<ByteArray>>()) // Corrected any() usage

        val configMap = mapOf<String, Any>(
            "url" to "http://test.com/data",
            "method" to "GET",
            "pollingIntervalMs" to 0L, // Fetch once to prevent scheduler issues in test
        )

        source.open(configMap, sourceContext) // This will now use the mockEngine

        // Allow some time for fetchData to be called if it's asynchronous (even without polling)
        // However, with pollingIntervalMs = 0, fetchData is called synchronously within open().
        // If open() itself spawns a coroutine for the initial fetch, a small delay might be needed.
        // For now, assume HttpSource's non-polling fetch is effectively synchronous for testing.

        val record = queue.poll(5, java.util.concurrent.TimeUnit.SECONDS)
        org.testng.Assert.assertNotNull(record, "Record should not be null")
        assertEquals(String(record!!.value, StandardCharsets.UTF_8), """{"key":"value"}""")
        assertEquals(record.properties["http_status_code"], HttpStatusCode.OK.value.toString())
    }

    @Test
    fun testPollingFetchesData() {
        var requestCount = 0
        mockEngine = MockEngine { request ->
            requestCount++
            respond(
                content = ByteReadChannel("""{"count":$requestCount}""".toByteArray(StandardCharsets.UTF_8)),
                status = HttpStatusCode.OK,
            )
        }
        source = HttpSource(mockEngine)
        doAnswer { invocation ->
            val record = invocation.getArgument<Record<ByteArray>>(0)
            queue.offer(record)
            null
        }.`when`(source).consume(any<Record<ByteArray>>()) // Corrected any() usage

        val configMap = mapOf<String, Any>(
            "url" to "http://test.com/poll",
            "pollingIntervalMs" to 50L, // Poll quickly for test
        )
        source.open(configMap, sourceContext)

        // Allow for a few polls
        Thread.sleep(275) // Approx 5 polls (0, 50, 100, 150, 200, 250)

        val records = mutableListOf<Record<ByteArray>>()
        queue.drainTo(records)

        assertTrue(records.size >= 5, "Should have polled at least 5 times. Found ${records.size}")
        // Check if requestCount was incremented in response body
        assertTrue(String(records.last().value).contains(""""count":${records.size}"""), "Last record should reflect poll count")
    }

    @Test
    fun testHttpError() {
        mockEngine = MockEngine { request ->
            respondError(HttpStatusCode.InternalServerError)
        }
        source = HttpSource(mockEngine)
        doAnswer { invocation ->
            val record = invocation.getArgument<Record<ByteArray>>(0)
            queue.offer(record)
            null
        }.`when`(source).consume(any<Record<ByteArray>>()) // Corrected any() usage

        val configMap = mapOf<String, Any>(
            "url" to "http://test.com/error",
            "pollingIntervalMs" to 0L, // Fetch once
        )
        source.open(configMap, sourceContext)

        val record = queue.poll(1, java.util.concurrent.TimeUnit.SECONDS)
        org.testng.Assert.assertNull(record, "No regular record should be produced on HTTP error")
        // We can't easily verify logger calls with Mockito on sourceContext.logger directly
        // unless sourceContext itself is a deeper mock or logger is injected.
        // For now, we check that no record is consumed.
    }

    // Example of how a Record implementation might be used within the source,
    // though the actual HttpRecord is an inner class in HttpSource.
    // This is more for conceptual understanding.
    private class TestRecord(private val data: String) : Record<ByteArray> {
        override fun getValue(): ByteArray = data.toByteArray(StandardCharsets.UTF_8)
        override fun getEventTime(): Optional<Long> = Optional.of(System.currentTimeMillis())
    }
}
