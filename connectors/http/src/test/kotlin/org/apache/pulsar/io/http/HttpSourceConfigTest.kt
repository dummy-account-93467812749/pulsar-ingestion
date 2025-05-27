package org.apache.pulsar.io.http

// Imports already seem to be correct for TestNG Assertions based on previous successful Spotless.
// The @Test annotation was the primary unresolved one if IDE didn't auto-import.
// Ensuring full path for clarity.
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNotNull
import org.testng.annotations.Test // This was missing if build failed on it
import java.io.File

class HttpSourceConfigTest {

    @Test
    fun testLoadConfigFromFile() {
        val yaml = """
            url: "http://localhost:8080/test"
            method: "POST"
            headers:
              "X-Custom-Header": "TestData"
            requestBody: "{ \"key\": \"value\" }"
            pollingIntervalMs: 10000
            connectTimeoutMs: 3000
            readTimeoutMs: 7000
        """.trimIndent()
        val tempFile = File.createTempFile("http-source-config", ".yaml")
        tempFile.writeText(yaml)
        tempFile.deleteOnExit()

        val config = HttpSourceConfig.load(tempFile.absolutePath)

        assertEquals(config.url, "http://localhost:8080/test")
        assertEquals(config.method, "POST")
        assertNotNull(config.headers)
        assertEquals(config.headers!!["X-Custom-Header"], "TestData")
        assertEquals(config.requestBody, "{ \"key\": \"value\" }")
        assertEquals(config.pollingIntervalMs, 10000L)
        assertEquals(config.connectTimeoutMs, 3000L)
        assertEquals(config.readTimeoutMs, 7000L)
    }

    @Test
    fun testLoadConfigFromMap() {
        val configMap = mapOf<String, Any>(
            "url" to "http://localhost:8080/map",
            "method" to "GET",
            "pollingIntervalMs" to 5000L,
        )

        val config = HttpSourceConfig.load(configMap)

        assertEquals(config.url, "http://localhost:8080/map")
        assertEquals(config.method, "GET")
        assertEquals(config.pollingIntervalMs, 5000L)
        // Check default values for non-provided fields
        assertEquals(config.connectTimeoutMs, 5000L) // Default from data class
        assertEquals(config.readTimeoutMs, 10000L) // Default from data class
    }

    @Test
    fun testDefaultValues() {
        val yaml = """
            url: "http://localhost:8080/defaults"
        """.trimIndent()
        val tempFile = File.createTempFile("http-source-config-defaults", ".yaml")
        tempFile.writeText(yaml)
        tempFile.deleteOnExit()

        val config = HttpSourceConfig.load(tempFile.absolutePath)

        assertEquals(config.url, "http://localhost:8080/defaults")
        assertEquals(config.method, "GET") // Default
        assertEquals(config.pollingIntervalMs, 5000L) // Default
        assertEquals(config.connectTimeoutMs, 5000L) // Default
        assertEquals(config.readTimeoutMs, 10000L) // Default
    }
}
