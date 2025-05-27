package org.apache.pulsar.io.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout // Added for timeout configuration
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import org.apache.pulsar.functions.api.Record
import org.apache.pulsar.io.core.PushSource
import org.apache.pulsar.io.core.SourceContext
import org.slf4j.LoggerFactory
import java.util.Optional
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// Modified primary constructor to accept HttpClientEngine directly
class HttpSource(private val engine: HttpClientEngine = CIO.create { /* Default CIO config if needed */ }) : PushSource<ByteArray>() {
    private lateinit var config: HttpSourceConfig
    private lateinit var httpClient: HttpClient // Will be initialized in open() using the provided engine
    private var scheduler: ScheduledExecutorService? = null
    private val running = AtomicBoolean(false)
    private var currentRecord: Record<ByteArray>? = null

    companion object {
        private val LOG = LoggerFactory.getLogger(HttpSource::class.java)
    }

    override fun open(configMap: Map<String, Any>, sourceContext: SourceContext) {
        config = HttpSourceConfig.load(configMap)
        if (config.url.isNullOrEmpty()) {
            throw IllegalArgumentException("HTTP URL must be set.")
        }

        // Initialize httpClient here using the engine passed in the constructor
        httpClient = HttpClient(engine) {
            install(HttpTimeout) {
                requestTimeoutMillis = config.readTimeoutMs ?: 10000L
                connectTimeoutMillis = config.connectTimeoutMs ?: 5000L
            }
        }

        running.set(true)

        if (config.pollingIntervalMs != null && config.pollingIntervalMs!! > 0) {
            scheduler = Executors.newSingleThreadScheduledExecutor()
            scheduler!!.scheduleAtFixedRate(this::fetchData, 0, config.pollingIntervalMs!!, TimeUnit.MILLISECONDS)
        } else {
            // If no polling interval, fetch once.
            fetchData()
        }
        LOG.info("HttpSource started with config: {}", config)
    }

    private fun fetchData() {
        if (!running.get()) {
            return
        }
        try {
            LOG.debug("Fetching data from URL: {}", config.url)
            runBlocking {
                val response: HttpResponse = httpClient.request(config.url!!) {
                    method = HttpMethod.parse(config.method ?: "GET")
                    val currentHeaders: java.util.Map<String, String>? = config.headers
                    currentHeaders?.forEach { key, value -> // Corrected lambda syntax for java.util.Map
                        headers.append(key, value)
                    }
                    config.requestBody?.let { requestBodyContent ->
                        setBody(requestBodyContent)
                    }
                }

                if (response.status.isSuccess()) {
                    val responseBody = response.readBytes()
                    val record = HttpRecord(responseBody, response.status.value, System.currentTimeMillis())
                    consume(record)
                    LOG.debug("Successfully fetched and processed data. Status: {}. Size: {} bytes.", response.status, responseBody.size)
                } else {
                    LOG.error("HTTP request failed with status: {}. Response: {}", response.status, response.bodyAsText())
                    // Optionally, you might want to emit a record with error information
                    // val errorRecord = HttpRecord("Error: ${response.status}".toByteArray(), response.status.value, System.currentTimeMillis(), isError = true)
                    // consume(errorRecord)
                }
            }
        } catch (e: Exception) {
            LOG.error("Error fetching data from HTTP source: ${config.url}", e)
            // Optionally, emit an error record
            // val errorRecord = HttpRecord("Exception: ${e.message}".toByteArray(), -1, System.currentTimeMillis(), isError = true)
            // consume(errorRecord)
        }
    }

    override fun close() {
        running.set(false)
        scheduler?.shutdown()
        try {
            if (scheduler?.awaitTermination(10, TimeUnit.SECONDS) == false) {
                scheduler?.shutdownNow()
            }
        } catch (ie: InterruptedException) {
            scheduler?.shutdownNow()
            Thread.currentThread().interrupt()
        }
        httpClient.close()
        LOG.info("HttpSource closed.")
    }

    private class HttpRecord(
        private val value: ByteArray,
        private val statusCode: Int,
        private val eventTime: Long,
        private val isError: Boolean = false,
    ) : Record<ByteArray> {
        override fun getValue(): ByteArray = value
        override fun getEventTime(): Optional<Long> = Optional.of(eventTime)
        override fun getProperties(): Map<String, String> {
            val props = mutableMapOf("http_status_code" to statusCode.toString())
            if (isError) {
                props["error"] = "true"
            }
            return props
        }
    }
}
