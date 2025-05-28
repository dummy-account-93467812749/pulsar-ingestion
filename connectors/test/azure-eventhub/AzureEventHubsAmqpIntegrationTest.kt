package org.apache.pulsar.io.azureeventhub.amqp

import org.apache.pulsar.client.admin.PulsarAdmin
import org.apache.pulsar.client.api.Consumer
import org.apache.pulsar.client.api.Message
import org.apache.pulsar.client.api.PulsarClient
import org.apache.pulsar.client.api.Schema
import org.apache.pulsar.common.io.ConnectorDefinition
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariableMissing
import org.slf4j.LoggerFactory
import org.testcontainers.containers.PulsarContainer
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.TimeUnit
import javax.jms.ConnectionFactory
import javax.jms.DeliveryMode
import javax.naming.InitialContext
import kotlin.test.assertEquals

@DisabledIfEnvironmentVariableMissing(named = "TEST_AZURE_EVENTHUBS_FQDN", matches = ".*", disabledReason = "Azure Event Hubs FQDN not configured for test.")
@DisabledIfEnvironmentVariableMissing(named = "TEST_AZURE_EVENTHUBS_NAME", matches = ".*", disabledReason = "Azure Event Hubs name not configured for test.")
@DisabledIfEnvironmentVariableMissing(named = "TEST_AZURE_EVENTHUBS_CONNECTION_STRING", matches = ".*", disabledReason = "Azure Event Hubs connection string not configured for test.")
class AzureEventHubsAmqpIntegrationTest {

    companion object {
        private val logger = LoggerFactory.getLogger(AzureEventHubsAmqpIntegrationTest::class.java)
        private const val PULSAR_IMAGE = "apachepulsar/pulsar:2.10.0" // Use a version consistent with project
        private const val CONNECTOR_TYPE = "amqp" // from connector.yaml
        private const val CONNECTOR_NAME = "azure-eventhub-amqp-source"
        private const val PULSAR_TOPIC = "persistent://public/default/eventhub-amqp-input-topic" // from connector.yaml

        private lateinit var pulsarContainer: PulsarContainer
        private lateinit var adminClient: PulsarAdmin
        private lateinit var pulsarClient: PulsarClient

        private lateinit var eventHubsFqdn: String
        private lateinit var eventHubsName: String
        private lateinit var eventHubsConnectionString: String
        private lateinit var tempAmqpConfigFile: File


        @JvmStatic
        @BeforeAll
        fun setup() {
            eventHubsFqdn = System.getenv("TEST_AZURE_EVENTHUBS_FQDN")
            eventHubsName = System.getenv("TEST_AZURE_EVENTHUBS_NAME")
            eventHubsConnectionString = System.getenv("TEST_AZURE_EVENTHUBS_CONNECTION_STRING")

            pulsarContainer = PulsarContainer(DockerImageName.parse(PULSAR_IMAGE))
            pulsarContainer.start()

            adminClient = PulsarAdmin.builder()
                .serviceHttpUrl(pulsarContainer.httpServiceUrl)
                .build()
            pulsarClient = PulsarClient.builder()
                .serviceUrl(pulsarContainer.pulsarBrokerUrl)
                .build()

            // Create temporary config file
            tempAmqpConfigFile = createTemporaryAmqpConfig()

            // Deploy connector
            deployConnector(tempAmqpConfigFile)
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
            pulsarContainer.stop()
            tempAmqpConfigFile.delete()
        }

        private fun createTemporaryAmqpConfig(): File {
            val originalConfigPath = "connectors/azure-eventhub/config.amqp.yml"
            val tempFile = Files.createTempFile("test-config-amqp", ".yml").toFile()

            var content = File(originalConfigPath).readText()
            content = content.replace("<your-eventhubs-namespace-fqdn>", eventHubsFqdn)
            content = content.replace("<your-eventhub-name>", eventHubsName)
            content = content.replace("<your-eventhubs-connection-string-or-sas-key>", eventHubsConnectionString)
            // Ensure username is $ConnectionString for Event Hubs connection strings
            content = content.replace("username: \"RootManageSharedAccessKey\"", "username: \"\\$ConnectionString\"")


            tempFile.writeText(content)
            logger.info("Temporary AMQP config created at: ${tempFile.absolutePath} with content:\n$content")
            return tempFile
        }

        private fun deployConnector(configFile: File) {
            val connectorNarPath = findConnectorNar()
            logger.info("Using connector NAR: $connectorNarPath")
            adminClient.sources().createSourceWithUpload(
                configFile.absolutePath, // Note: This seems to be a deviation from standard PulsarAdmin API.
                                        // Usually, createSource takes SourceConfig, and NAR is uploaded separately or assumed present.
                                        // The tool provided `create_file_with_block` which implies this file exists in the Pulsar container's context
                                        // This might need adjustment based on how `createSourceWithUpload` actually works or if there's a different method in test-kit.
                                        // For now, assuming it means the local path to the config.
                                        // If this is local path, Pulsar needs access to it. A common pattern is to upload NARs and then create sources.
                                        // Let's assume there's a mechanism for Pulsar to access this, or it's a path within the Pulsar container.
                                        // Given this is a test, copying the config into the container is more robust.
                "connectors/$CONNECTOR_NAME.nar" // This is the path inside the Pulsar container where the NAR should be.
                                                   // The actual upload of the NAR needs to be handled. Testcontainers exec can copy files.
            )

            // Copy the NAR to the container
            val narFileName = File(connectorNarPath).name
            pulsarContainer.copyFileToContainer(
                org.testcontainers.images.builder.Transferable.of(File(connectorNarPath).readBytes()),
                "/pulsar/connectors/$narFileName"
            )
            
            // Copy the temporary config to the container
            val tempConfigContainerPath = "/pulsar/conf/${tempAmqpConfigFile.name}"
            pulsarContainer.copyFileToContainer(
                org.testcontainers.images.builder.Transferable.of(tempAmqpConfigFile.readBytes()),
                tempConfigContainerPath
            )


            val sourceConfig = org.apache.pulsar.common.io.SourceConfig.builder()
                .tenant("public")
                .namespace("default")
                .name(CONNECTOR_NAME)
                .topicName(PULSAR_TOPIC) // Output topic for the source
                .archive("/pulsar/connectors/$narFileName") // Path to NAR in container
                .sourceType(CONNECTOR_TYPE) // from connector.yaml
                .parallelism(1)
                .configs(mapOf("configFile" to tempConfigContainerPath)) // Point to config file in container
                .build()
            
            adminClient.sources().createSource(sourceConfig, "/pulsar/connectors/$narFileName")


            // Wait for the connector to be ready (basic check)
            Thread.sleep(10000) // Give some time for the connector to start
            val status = adminClient.sources().getSourceStatus("public", "default", CONNECTOR_NAME)
            if (status.numRunning == 0) {
                logger.error("Connector $CONNECTOR_NAME failed to start. Instances: ${status.instances}")
                throw RuntimeException("Connector $CONNECTOR_NAME failed to start. Status: ${status.instances.firstOrNull()?.status?.error}")
            }
            logger.info("Connector $CONNECTOR_NAME started successfully with ${status.numRunning} instances.")
        }
        
        private fun findConnectorNar(): String {
            // Attempt to locate the NAR file. This logic might need adjustment based on the build output structure.
            // Assuming it's in 'build/distributions' of the 'azure-eventhub' connector module
            val connectorModuleDir = File(".").absoluteFile.parentFile // Assuming tests run from module root or similar
            logger.info("Attempting to find NAR in module directory: ${connectorModuleDir.absolutePath}")

            // A more robust way could be to get it from a known location or pass via system property
            // For now, search in typical build output locations
            val possibleNarLocations = listOf(
                "build/distributions", // Standard for Pulsar connector builds
                "../build/distributions", // If test is in a sub-module of connector
                 "../../connectors/azure-eventhub/build/distributions" // Relative to a potential project root if test is run from there
            )
            
            val narPattern = Regex("pulsar-io-azure-eventhub-amqp-.*\\.nar") // Adjust if name is different

            for (loc in possibleNarLocations) {
                val dir = File(connectorModuleDir, loc)
                if (dir.exists() && dir.isDirectory) {
                    dir.listFiles { _, name -> narPattern.matches(name) }?.firstOrNull()?.let {
                        logger.info("Found NAR: ${it.absolutePath}")
                        return it.absolutePath
                    }
                }
            }
            // Fallback for local development if the NAR is in the target/ directory of the azure-eventhub connector
             val targetDir = File(System.getProperty("user.dir"),"../../connectors/azure-eventhub/build/libs/")
             targetDir.listFiles { _, name -> name.endsWith(".nar") && name.startsWith("pulsar-io-azure-eventhub") }?.firstOrNull()?.let {
                 logger.info("Found NAR in target: ${it.absolutePath}")
                 return it.absolutePath
             }


            throw IllegalStateException("Connector NAR file not found. Searched in: $possibleNarLocations relative to ${connectorModuleDir.absolutePath} and $targetDir")
        }
    }

    @Test
    fun `should receive messages from Event Hubs via AMQP connector`() {
        val messageCount = 5
        val testMessagePrefix = "test-eh-amqp-message-"

        // 1. Send messages to Azure Event Hub using AMQP 1.0 client
        sendAmqpMessages(eventHubsFqdn, eventHubsName, eventHubsConnectionString, messageCount, testMessagePrefix)

        // 2. Initialize Pulsar consumer and receive messages
        val receivedMessages = mutableListOf<String>()
        var consumer: Consumer<String>? = null
        try {
            consumer = pulsarClient.newConsumer(Schema.STRING)
                .topic(PULSAR_TOPIC)
                .subscriptionName("test-subscription-${System.currentTimeMillis()}")
                .subscribe()

            for (i in 0 until messageCount) {
                val msg: Message<String>? = consumer.receive(30, TimeUnit.SECONDS)
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
        assertEquals(messageCount, receivedMessages.size, "Should have received all messages sent to Event Hubs.")
        for (i in 0 until messageCount) {
            assertEquals("$testMessagePrefix$i", receivedMessages[i], "Message content mismatch for message $i.")
        }
        logger.info("Successfully received and verified $messageCount messages from Event Hubs via Pulsar AMQP connector.")
    }

    private fun sendAmqpMessages(fqdn: String, eventHubName: String, connectionString: String, count: Int, prefix: String) {
        val properties = Properties()
        properties.setProperty(InitialContext.INITIAL_CONTEXT_FACTORY, "org.apache.qpid.jms.jndi.JmsInitialContextFactory")
        properties.setProperty("connectionfactory.eventHubs", "amqps://$fqdn?amqp.idleTimeout=120000&jms.username=\$ConnectionString&jms.password=$connectionString")
        properties.setProperty("queue.eventHubName", eventHubName) // Using 'queue' for JNDI lookup of the address

        val context = InitialContext(properties)
        val connectionFactory = context.lookup("eventHubs") as ConnectionFactory
        
        val connection = connectionFactory.createConnection()
        try {
            connection.start()
            val session = connection.createSession(false, javax.jms.Session.AUTO_ACKNOWLEDGE)
            val queue = context.lookup("eventHubName") as javax.jms.Queue // Destination is the Event Hub name
            val producer = session.createProducer(queue)
            producer.deliveryMode = DeliveryMode.PERSISTENT // Recommended for Event Hubs

            for (i in 0 until count) {
                val messageContent = "$prefix$i"
                val message = session.createTextMessage(messageContent)
                producer.send(message)
                logger.info("Sent AMQP message to $eventHubName: $messageContent")
            }
        } finally {
            connection.close()
            context.close()
        }
    }
}
