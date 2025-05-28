package org.apache.pulsar.io.rabbitmq

import com.rabbitmq.client.ConnectionFactory
import org.apache.pulsar.client.admin.PulsarAdmin
import org.apache.pulsar.client.api.*
import org.junit.jupiter.api.*
import org.slf4j.LoggerFactory
import org.testcontainers.containers.PulsarContainer
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS) // Allows non-static @BeforeAll and @AfterAll
class RabbitMQSourceIntegrationTest {

    companion object {
        private val logger = LoggerFactory.getLogger(RabbitMQSourceIntegrationTest::class.java)
        private const val PULSAR_IMAGE = "apachepulsar/pulsar:2.10.0"
        private val RABBITMQ_IMAGE = DockerImageName.parse("rabbitmq:3.9-management-alpine") // Use a recent version

        private const val CONNECTOR_TYPE = "rabbitmq" // from connector.yaml
        private const val CONNECTOR_NAME = "rabbitmq-source-test"
        private const val PULSAR_OUTPUT_TOPIC = "persistent://public/default/rabbitmq-topic" // from connector.yaml
        private const val RABBITMQ_TEST_QUEUE = "test-rabbitmq-input-queue"
        private const val RABBITMQ_VHOST = "/"
    }

    private lateinit var pulsarContainer: PulsarContainer
    private lateinit var rabbitMQContainer: RabbitMQContainer
    private lateinit var adminClient: PulsarAdmin
    private lateinit var pulsarClient: PulsarClient
    private lateinit var rabbitMQConnectionFactory: ConnectionFactory

    private lateinit var tempRabbitMQConfigFile: File

    @BeforeAll
    fun setup() {
        pulsarContainer = PulsarContainer(DockerImageName.parse(PULSAR_IMAGE)).apply {
            waitingFor(Wait.forHttp("/admin/v2/clusters/standalone").forStatusCode(200).forPort(8080))
        }
        pulsarContainer.start()

        rabbitMQContainer = RabbitMQContainer(RABBITMQ_IMAGE)
        // Default user/pass for RabbitMQContainer is guest/guest, which matches sample config if placeholders are empty.
        // If specific user/pass were set on container, they'd need to be reflected in config.
        rabbitMQContainer.start()

        logger.info("Pulsar container started. HTTP Service URL: ${pulsarContainer.httpServiceUrl}, Broker URL: ${pulsarContainer.pulsarBrokerUrl}")
        logger.info("RabbitMQ container started. Host: ${rabbitMQContainer.host}, AMQP Port: ${rabbitMQContainer.amqpPort}, User: ${rabbitMQContainer.adminUsername}")


        adminClient = PulsarAdmin.builder()
            .serviceHttpUrl(pulsarContainer.httpServiceUrl)
            .build()
        pulsarClient = PulsarClient.builder()
            .serviceUrl(pulsarContainer.pulsarBrokerUrl)
            .build()

        rabbitMQConnectionFactory = ConnectionFactory().apply {
            host = rabbitMQContainer.host
            port = rabbitMQContainer.amqpPort
            username = rabbitMQContainer.adminUsername // "guest"
            password = rabbitMQContainer.adminPassword // "guest"
            virtualHost = RABBITMQ_VHOST
        }
        
        // Declare the queue in RabbitMQ
        declareTestQueue()

        tempRabbitMQConfigFile = createTemporaryRabbitMQConfig()
        deployConnector(tempRabbitMQConfigFile)
    }
    
    private fun declareTestQueue() {
        try {
            rabbitMQConnectionFactory.newConnection().use { connection ->
                connection.createChannel().use { channel ->
                    channel.queueDeclare(RABBITMQ_TEST_QUEUE, true, false, false, null)
                    logger.info("RabbitMQ queue '$RABBITMQ_TEST_QUEUE' declared successfully.")
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to declare RabbitMQ queue '$RABBITMQ_TEST_QUEUE'", e)
            throw e
        }
    }


    @AfterAll
    fun teardown() {
        try {
            adminClient.sources().deleteSource("public", "default", CONNECTOR_NAME)
            logger.info("Successfully deleted connector $CONNECTOR_NAME")
        } catch (e: Exception) {
            logger.warn("Failed to delete connector $CONNECTOR_NAME", e)
        }
        adminClient.close()
        pulsarClient.close()
        // RabbitMQ client connections are managed per operation or closed by RabbitMQContainer stop
        rabbitMQContainer.stop()
        pulsarContainer.stop()
        if (::tempRabbitMQConfigFile.isInitialized && tempRabbitMQConfigFile.exists()) {
            tempRabbitMQConfigFile.delete()
        }
    }

    private fun createTemporaryRabbitMQConfig(): File {
        val originalConfigPath = "connectors/rabbitmq/config.sample.yml"
        val tempFile = Files.createTempFile("test-config-rabbitmq", ".yml").toFile()

        var content = File(originalConfigPath).readText()
        content = content.replace(Regex("host:\\s*\".*\""), "host: \"${rabbitMQContainer.host}\"")
        content = content.replace(Regex("port:\\s*\\d+"), "port: ${rabbitMQContainer.amqpPort}")
        content = content.replace(Regex("username:\\s*\".*\""), "username: \"${rabbitMQContainer.adminUsername}\"")
        content = content.replace(Regex("password:\\s*\".*\""), "password: \"${rabbitMQContainer.adminPassword}\"")
        content = content.replace(Regex("queueName:\\s*\".*\""), "queueName: \"$RABBITMQ_TEST_QUEUE\"")
        content = content.replace(Regex("virtualHost:\\s*\".*\""), "virtualHost: \"$RABBITMQ_VHOST\"")


        tempFile.writeText(content)
        logger.info("Temporary RabbitMQ config created at: ${tempFile.absolutePath} with content:\n$content")
        return tempFile
    }

    private fun deployConnector(configFile: File) {
        val connectorNarPath = findConnectorNar()
        logger.info("Using connector NAR: $connectorNarPath")
        val narFileName = File(connectorNarPath).name

        pulsarContainer.copyFileToContainer(
            org.testcontainers.images.builder.Transferable.of(File(connectorNarPath).readBytes()),
            "/pulsar/connectors/$narFileName"
        )

        val tempConfigContainerPath = "/pulsar/conf/${configFile.name}"
        pulsarContainer.copyFileToContainer(
            org.testcontainers.images.builder.Transferable.of(configFile.readBytes()),
            tempConfigContainerPath
        )

        val sourceConfig = org.apache.pulsar.common.io.SourceConfig.builder()
            .tenant("public")
            .namespace("default")
            .name(CONNECTOR_NAME)
            .topicName(PULSAR_OUTPUT_TOPIC)
            .archive("/pulsar/connectors/$narFileName")
            .sourceType(CONNECTOR_TYPE)
            .parallelism(1)
            .configs(mapOf("configFile" to tempConfigContainerPath))
            .build()

        adminClient.sources().createSource(sourceConfig, "/pulsar/connectors/$narFileName")

        var running = false
        for (i in 1..30) {
            try {
                val status = adminClient.sources().getSourceStatus("public", "default", CONNECTOR_NAME)
                if (status.numRunning > 0 && status.instances.isNotEmpty() && status.instances.first().status.running) {
                    running = true
                    logger.info("Connector $CONNECTOR_NAME started successfully with ${status.numRunning} instances.")
                    break
                }
                logger.info("Waiting for connector $CONNECTOR_NAME to start... (${status.numRunning} running). Error: ${status.instances.firstOrNull()?.status?.error}")
            } catch (e: Exception) {
                logger.warn("Error getting connector status: ${e.message}")
            }
            Thread.sleep(1000)
        }
        if (!running) {
            val status = try { adminClient.sources().getSourceStatus("public", "default", CONNECTOR_NAME) } catch (e: Exception) { null }
            logger.error("Connector $CONNECTOR_NAME failed to start. Status: $status")
            throw RuntimeException("Connector $CONNECTOR_NAME failed to start. Status: ${status?.instances?.firstOrNull()?.status?.error}")
        }
    }

    private fun findConnectorNar(): String {
        val projectRoot = File(System.getProperty("user.dir")).parentFile.parentFile // Heuristic
        val connectorBuildDir = File(projectRoot, "connectors/rabbitmq/build/libs")
        logger.info("Attempting to find NAR in directory: ${connectorBuildDir.absolutePath}")
        
        connectorBuildDir.listFiles { _, name -> name.endsWith(".nar") && name.startsWith("pulsar-io-rabbitmq") }?.firstOrNull()?.let {
            logger.info("Found NAR: ${it.absolutePath}")
            return it.absolutePath
        }
        val targetDir = File(System.getProperty("user.dir"), "../../../connectors/rabbitmq/build/libs/")
        targetDir.listFiles { _, name -> name.endsWith(".nar") && name.startsWith("pulsar-io-rabbitmq") }?.firstOrNull()?.let {
            logger.info("Found NAR in target: ${it.absolutePath}")
            return it.absolutePath
        }
        throw IllegalStateException("Connector NAR file (pulsar-io-rabbitmq-*.nar) not found. Searched standard locations.")
    }

    @Test
    fun `should receive messages from RabbitMQ queue via RabbitMQ source connector`() {
        val messageCount = 4 
        val testMessagePrefix = "rabbitmq-test-payload-"

        // 1. Send messages to RabbitMQ
        rabbitMQConnectionFactory.newConnection().use { connection ->
            connection.createChannel().use { channel ->
                for (i in 0 until messageCount) {
                    val payload = "$testMessagePrefix$i"
                    channel.basicPublish("", RABBITMQ_TEST_QUEUE, null, payload.toByteArray(Charsets.UTF_8))
                    logger.info("Sent message to RabbitMQ queue '$RABBITMQ_TEST_QUEUE': $payload")
                }
            }
        }

        // 2. Initialize Pulsar consumer and receive messages
        val receivedMessages = mutableListOf<String>()
        var consumer: Consumer<String>? = null
        try {
            consumer = pulsarClient.newConsumer(Schema.STRING)
                .topic(PULSAR_OUTPUT_TOPIC)
                .subscriptionName("test-rabbitmq-source-subscription-${System.currentTimeMillis()}")
                .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                .subscribe()

            for (i in 0 until messageCount) {
                val msg: Message<String>? = consumer.receive(30, TimeUnit.SECONDS)
                if (msg != null) {
                    receivedMessages.add(msg.value)
                    logger.info("Received message from Pulsar: ${msg.value}")
                    consumer.acknowledge(msg)
                } else {
                    logger.warn("Timed out waiting for message #$i from Pulsar (received ${receivedMessages.size} so far).")
                    break
                }
            }
        } finally {
            consumer?.close()
        }

        // 3. Assertions
        assertEquals(messageCount, receivedMessages.size, "Should have received all messages sent to RabbitMQ.")
        for (i in 0 until messageCount) {
            assertEquals("$testMessagePrefix$i", receivedMessages[i], "Message content mismatch for message $i.")
        }
        logger.info("Successfully received and verified $messageCount messages from RabbitMQ via Pulsar RabbitMQ source connector.")
    }
}
