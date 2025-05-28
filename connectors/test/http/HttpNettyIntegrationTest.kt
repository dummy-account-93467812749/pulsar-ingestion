package org.apache.pulsar.io.http.netty

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import org.apache.pulsar.client.admin.PulsarAdmin
import org.apache.pulsar.client.api.Consumer
import org.apache.pulsar.client.api.Message
import org.apache.pulsar.client.api.PulsarClient
import org.apache.pulsar.client.api.Schema
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.testcontainers.containers.PulsarContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpNettyIntegrationTest {

    companion object {
        private val logger = LoggerFactory.getLogger(HttpNettyIntegrationTest::class.java)
        private const val PULSAR_IMAGE = "apachepulsar/pulsar:2.10.0" // Use a version consistent with project
        private const val CONNECTOR_TYPE = "netty" // from connector.yaml
        private const val CONNECTOR_NAME = "http-netty-source"
        private const val PULSAR_TOPIC = "persistent://public/default/http-netty-input-topic" // from connector.yaml

        private lateinit var pulsarContainer: PulsarContainer
        private lateinit var adminClient: PulsarAdmin
        private lateinit var pulsarClient: PulsarClient
        private lateinit var httpClient: HttpClient

        private lateinit var tempHttpConfigFile: File
        private var testHttpPort: Int = 0


        @JvmStatic
        @BeforeAll
        fun setup() {
            pulsarContainer = PulsarContainer(DockerImageName.parse(PULSAR_IMAGE)).apply {
                // Expose a dynamic port for the Netty connector to listen on
                // We'll configure the connector to use this port
                // Testcontainers will find a free port on the host
                // For now, we'll pick a port and ensure the config uses it.
                // A truly dynamic port would require Testcontainers to map it and us to retrieve it.
                // Let's use a fixed high port for testing to avoid common conflicts.
                // Or, better, let Testcontainers allocate one for Pulsar and use that info.
                // The Netty connector runs *inside* Pulsar container. So it needs a port *inside* the container.
                // We'll send requests to the *mapped* port on the host.
                // For simplicity, let's fix the internal port and let TC map it.
                // The config.http.yml defines the port the Netty server *inside* the container listens on.
                // We need to make sure our HTTP client POSTs to the host port mapped to this internal port.
                // The PulsarContainer by default maps broker and http service. We need to map our custom Netty port.
                // Let's try to get a free port from the system for the test.
                testHttpPort = findFreePort()
                withExposedPorts(testHttpPort) // Expose the port for the Netty connector
                waitingFor(Wait.forHttp("/admin/v2/clusters/standalone").forStatusCode(200).forPort(8080))
            }
            pulsarContainer.start()
            
            logger.info("Pulsar container started. HTTP Service URL: ${pulsarContainer.httpServiceUrl}, Broker URL: ${pulsarContainer.pulsarBrokerUrl}")
            logger.info("Netty test HTTP port (internal) will be $testHttpPort. Mapped port on host: ${pulsarContainer.getMappedPort(testHttpPort)}")


            adminClient = PulsarAdmin.builder()
                .serviceHttpUrl(pulsarContainer.httpServiceUrl)
                .build()
            pulsarClient = PulsarClient.builder()
                .serviceUrl(pulsarContainer.pulsarBrokerUrl)
                .build()
            httpClient = HttpClient(CIO)

            tempHttpConfigFile = createTemporaryHttpConfig(testHttpPort)
            deployConnector(tempHttpConfigFile)
        }

        private fun findFreePort(): Int {
            return java.net.ServerSocket(0).use { it.localPort }
        }


        @JvmStatic
        @AfterAll
        fun teardown() {
            try {
                adminClient.sources().deleteSource("public", "default", CONNECTOR_NAME)
            } catch (e: Exception) {
                logger.warn("Failed to delete connector $CONNECTOR_NAME", e)
            }
            adminClient.close()
            pulsarClient.close()
            httpClient.close()
            pulsarContainer.stop()
            tempHttpConfigFile.delete()
        }

        private fun createTemporaryHttpConfig(port: Int): File {
            val originalConfigPath = "connectors/http/config.http.yml" // Path to the template config
            val tempFile = Files.createTempFile("test-config-http", ".yml").toFile()

            var content = File(originalConfigPath).readText()
            content = content.replace(Regex("port:\\s*\\d+"), "port: $port") // Replace port
            content = content.replace(Regex("host:\\s*\".*\""), "host: \"0.0.0.0\"") // Ensure listening on all interfaces in container

            tempFile.writeText(content)
            logger.info("Temporary HTTP config created at: ${tempFile.absolutePath} with port $port. Content:\n$content")
            return tempFile
        }

        private fun deployConnector(configFile: File) {
            val connectorNarPath = findConnectorNar()
            logger.info("Using connector NAR: $connectorNarPath")
            val narFileName = File(connectorNarPath).name

            // Copy NAR to Pulsar container
            pulsarContainer.copyFileToContainer(
                org.testcontainers.images.builder.Transferable.of(File(connectorNarPath).readBytes()),
                "/pulsar/connectors/$narFileName"
            )
            
            // Copy temporary config to Pulsar container
            val tempConfigContainerPath = "/pulsar/conf/${configFile.name}"
            pulsarContainer.copyFileToContainer(
                org.testcontainers.images.builder.Transferable.of(configFile.readBytes()),
                tempConfigContainerPath
            )

            val sourceConfig = org.apache.pulsar.common.io.SourceConfig.builder()
                .tenant("public")
                .namespace("default")
                .name(CONNECTOR_NAME)
                .topicName(PULSAR_TOPIC)
                .archive("/pulsar/connectors/$narFileName") // Path to NAR in container
                .sourceType(CONNECTOR_TYPE)
                .parallelism(1)
                .configs(mapOf("configFile" to tempConfigContainerPath)) // Point to config file in container
                .build()
            
            adminClient.sources().createSource(sourceConfig, "/pulsar/connectors/$narFileName")

            // Wait for the connector to be ready
            var running = false
            for (i in 1..30) { // Check for 30 seconds
                try {
                    val status = adminClient.sources().getSourceStatus("public", "default", CONNECTOR_NAME)
                    if (status.numRunning > 0 && status.instances.isNotEmpty() && status.instances.first().status.running) {
                        running = true
                        logger.info("Connector $CONNECTOR_NAME started successfully with ${status.numRunning} instances.")
                        break
                    }
                    logger.info("Waiting for connector $CONNECTOR_NAME to start... (${status.numRunning} running). Status: ${status.instances.firstOrNull()?.status?.error}")
                } catch (e: Exception) {
                    logger.warn("Error getting connector status: ${e.message}")
                }
                Thread.sleep(1000)
            }
            if (!running) {
                 val status = adminClient.sources().getSourceStatus("public", "default", CONNECTOR_NAME)
                logger.error("Connector $CONNECTOR_NAME failed to start. Instances: ${status.instances}")
                throw RuntimeException("Connector $CONNECTOR_NAME failed to start. Status: ${status.instances.firstOrNull()?.status?.error}")
            }
        }
        
        private fun findConnectorNar(): String {
            val connectorModuleDir = File(".").absoluteFile.parentFile 
            logger.info("Attempting to find NAR in module directory: ${connectorModuleDir.absolutePath}")
            val possibleNarLocations = listOf(
                "build/distributions",
                 "../../connectors/http/build/distributions" 
            )
            val narPattern = Regex("pulsar-io-http-netty-.*\\.nar") // Adjusted for potential new naming

            for (loc in possibleNarLocations) {
                val dir = File(connectorModuleDir, loc)
                if (dir.exists() && dir.isDirectory) {
                    dir.listFiles { _, name -> narPattern.matches(name) }?.firstOrNull()?.let {
                        logger.info("Found NAR: ${it.absolutePath}")
                        return it.absolutePath
                    }
                }
            }
            // Fallback for local development
             val targetDir = File(System.getProperty("user.dir"),"../../connectors/http/build/libs/")
             targetDir.listFiles { _, name -> name.endsWith(".nar") && name.startsWith("pulsar-io-http") }?.firstOrNull()?.let {
                 logger.info("Found NAR in target: ${it.absolutePath}")
                 return it.absolutePath
             }
            throw IllegalStateException("Connector NAR file not found for http-netty. Searched in: $possibleNarLocations relative to ${connectorModuleDir.absolutePath} and $targetDir")
        }
    }

    @Test
    fun `should receive messages from HTTP POST requests via Netty connector`() {
        val messageCount = 5
        val testMessagePrefix = "http-netty-test-payload-"
        val hostMappedPort = pulsarContainer.getMappedPort(testHttpPort)

        // 1. Send HTTP POST requests
        runBlocking {
            for (i in 0 until messageCount) {
                val payload = "$testMessagePrefix$i"
                try {
                    val response: HttpResponse = httpClient.post("http://localhost:$hostMappedPort/") {
                        setBody(payload)
                        contentType(ContentType.Text.Plain)
                    }
                    assertEquals(HttpStatusCode.OK, response.status, "HTTP POST request failed for message $i")
                    logger.info("Sent HTTP POST: $payload, Response status: ${response.status.value}")
                } catch (e: Exception) {
                    logger.error("Error sending HTTP POST message $i: ${e.message}", e)
                    throw e // Fail test if send fails
                }
            }
        }

        // 2. Initialize Pulsar consumer and receive messages
        val receivedMessages = mutableListOf<String>()
        var consumer: Consumer<String>? = null
        try {
            consumer = pulsarClient.newConsumer(Schema.STRING)
                .topic(PULSAR_TOPIC)
                .subscriptionName("test-http-subscription-${System.currentTimeMillis()}")
                .subscribe()

            for (i in 0 until messageCount) {
                val msg: Message<String>? = consumer.receive(20, TimeUnit.SECONDS) // Increased timeout
                if (msg != null) {
                    receivedMessages.add(msg.value)
                    logger.info("Received message from Pulsar: ${msg.value}")
                    consumer.acknowledge(msg)
                } else {
                    logger.warn("Timed out waiting for message $i from Pulsar.")
                    break 
                }
            }
        } finally {
            consumer?.close()
        }

        // 3. Assertions
        assertEquals(messageCount, receivedMessages.size, "Should have received all HTTP POST payloads.")
        for (i in 0 until messageCount) {
            assertEquals("$testMessagePrefix$i", receivedMessages[i], "Message content mismatch for message $i.")
        }
        logger.info("Successfully received and verified $messageCount messages from HTTP POST requests via Netty connector.")
    }
}
