package org.apache.pulsar.io.http

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine // Ensure this is present
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.apache.pulsar.functions.api.Record
import org.apache.pulsar.io.core.SourceContext
import org.mockito.Mockito.mock
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.slf4j.LoggerFactory
import org.testng.Assert
import org.testng.annotations.AfterClass
import org.testng.annotations.BeforeClass
import org.testng.annotations.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@Serializable
data class TestData(val message: String, val count: Int)

class HttpSourceIntegrationTest {

    private lateinit var server: ApplicationEngine
    private var serverPort: Int = 0
    private val records = LinkedBlockingQueue<Record<ByteArray>>()
    private lateinit var source: HttpSource
    private lateinit var sourceContext: SourceContext

    companion object {
        private val LOG = LoggerFactory.getLogger(HttpSourceIntegrationTest::class.java)
    }

    @BeforeClass
    fun startServer() {
        server = embeddedServer(Netty, port = 0) {
            install(ContentNegotiation) {
                json()
            }
            routing {
                get("/test-data") {
                    call.respond(TestData("hello from server", 1))
                }
                get("/test-poll") {
                    val count = call.request.queryParameters["count"]?.toIntOrNull() ?: 0
                    call.respond(TestData("polling data", count))
                }
                get("/error-500") {
                    call.respond(io.ktor.http.HttpStatusCode.InternalServerError, "Simulated server error")
                }
            }
        }
        server.start(wait = false)
        val connector = server.environment.connectors.first { it.type == io.ktor.server.engine.ApplicationEngine.ConnectorType.SERVER } // Explicit fully qualified name
        serverPort = connector.port
        LOG.info("Ktor test server started on port $serverPort")

        sourceContext = mock(SourceContext::class.java)
        val logger = mock(org.slf4j.Logger::class.java)
        org.mockito.Mockito.`when`(sourceContext.logger).thenReturn(logger)
    }

    @AfterClass
    fun stopServer() {
        server.stop(1000, 5000) // Graceful shutdown
        LOG.info("Ktor test server stopped.")
    }

    private fun setupSourceAndConsume() {
        source = HttpSource() // Uses default CIO engine, which is fine for hitting a real local server
        records.clear()
        doAnswer { invocation ->
            val record = invocation.getArgument<Record<ByteArray>>(0)
            records.offer(record)
            null
        }.`when`(source).consume(anyOrNull())
    }

    @Test
    fun testFetchDataOnce() {
        setupSourceAndConsume()
        val config = mapOf(
            "url" to "http://localhost:$serverPort/test-data",
            "method" to "GET",
            "pollingIntervalMs" to 0L, // Fetch once
        )
        source.open(config, sourceContext)

        val record = records.poll(5, TimeUnit.SECONDS)
        Assert.assertNotNull(record, "Should have received a record")
        val responseData = String(record!!.value)
        LOG.info("Received data: $responseData")
        Assert.assertEquals(responseData, """{"message":"hello from server","count":1}""")
        Assert.assertNotNull(record.eventTime.get())
        Assert.assertEquals(record.properties["http_status_code"], "200")
        source.close()
    }

    @Test
    fun testPollingIntegration() {
        setupSourceAndConsume()
        val config = mapOf(
            "url" to "http://localhost:$serverPort/test-poll", // Fixed URL for polling
            "method" to "GET",
            "pollingIntervalMs" to 100L,
        )

        source.open(config, sourceContext)
        Thread.sleep(350) // Allow for ~3 polls

        val receivedRecords = mutableListOf<Record<ByteArray>>()
        records.drainTo(receivedRecords)

        Assert.assertTrue(receivedRecords.size >= 3, "Should have polled at least 3 times. Found ${receivedRecords.size}")
        receivedRecords.forEach {
            val responseData = String(it.value)
            Assert.assertEquals(responseData, """{"message":"polling data","count":0}""") // Server always returns count 0 for this fixed URL
        }
        source.close()
    }

    @Test
    fun testHttpErrorHandling() {
        setupSourceAndConsume()
        val config = mapOf(
            "url" to "http://localhost:$serverPort/error-500",
            "method" to "GET",
            "pollingIntervalMs" to 0L,
        )
        source.open(config, sourceContext)
        val record = records.poll(1, TimeUnit.SECONDS)
        Assert.assertNull(record, "No record should be produced on HTTP 500 error by default")
        source.close()
    }
}
