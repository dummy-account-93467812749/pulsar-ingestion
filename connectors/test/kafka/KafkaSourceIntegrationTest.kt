package org.apache.pulsar.io.kafka

import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.pulsar.client.admin.PulsarAdmin
import org.apache.pulsar.client.api.Consumer
import org.apache.pulsar.client.api.Message
import org.apache.pulsar.client.api.PulsarClient
import org.apache.pulsar.client.api.Schema
import org.junit.jupiter.api.*
import org.slf4j.LoggerFactory
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.PulsarContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.nio.file.Files
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS) // Allows non-static @BeforeAll and @AfterAll
class KafkaSourceIntegrationTest {

    companion object {
        private val logger = LoggerFactory.getLogger(KafkaSourceIntegrationTest::class.java)
        private const val PULSAR_IMAGE = "apachepulsar/pulsar:2.10.0" // Use a version consistent with project
        private val KAFKA_IMAGE = DockerImageName.parse("confluentinc/cp-kafka:7.3.2") // Use a compatible Kafka version

        private const val CONNECTOR_TYPE = "kafka" // from connector.yaml
        private const val CONNECTOR_NAME = "kafka-source-test"
        // This is the Pulsar topic the Kafka source will produce to, as defined in connectors/kafka/connector.yaml
        private const val PULSAR_OUTPUT_TOPIC = "persistent://public/default/kafka-topic"
        private const val KAFKA_TEST_TOPIC = "test-kafka-input-topic"
    }

    private lateinit var pulsarContainer: PulsarContainer
    private lateinit var kafkaContainer: KafkaContainer
    private lateinit var adminClient: PulsarAdmin
    private lateinit var pulsarClient: PulsarClient
    private lateinit var kafkaProducer: KafkaProducer<String, String>

    private lateinit var tempKafkaConfigFile: File

    @BeforeAll
    fun setup() {
        pulsarContainer = PulsarContainer(DockerImageName.parse(PULSAR_IMAGE)).apply {
            waitingFor(Wait.forHttp("/admin/v2/clusters/standalone").forStatusCode(200).forPort(8080))
        }
        pulsarContainer.start()

        kafkaContainer = KafkaContainer(KAFKA_IMAGE)
        kafkaContainer.start()

        logger.info("Pulsar container started. HTTP Service URL: ${pulsarContainer.httpServiceUrl}, Broker URL: ${pulsarContainer.pulsarBrokerUrl}")
        logger.info("Kafka container started. Bootstrap Servers: ${kafkaContainer.bootstrapServers}")

        adminClient = PulsarAdmin.builder()
            .serviceHttpUrl(pulsarContainer.httpServiceUrl)
            .build()
        pulsarClient = PulsarClient.builder()
            .serviceUrl(pulsarContainer.pulsarBrokerUrl)
            .build()

        val producerProps = Properties()
        producerProps[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] = kafkaContainer.bootstrapServers
        producerProps[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java.name
        producerProps[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java.name
        kafkaProducer = KafkaProducer<String, String>(producerProps)

        tempKafkaConfigFile = createTemporaryKafkaConfig()
        deployConnector(tempKafkaConfigFile)
    }

    @AfterAll
    fun teardown() {
        try {
            adminClient.sources().deleteSource("public", "default", CONNECTOR_NAME)
            logger.info("Successfully deleted connector $CONNECTOR_NAME")
        } catch (e: Exception) {
            logger.warn("Failed to delete connector $CONNECTOR_NAME", e)
        }
        kafkaProducer.close()
        adminClient.close()
        pulsarClient.close()
        kafkaContainer.stop()
        pulsarContainer.stop()
        if (::tempKafkaConfigFile.isInitialized && tempKafkaConfigFile.exists()) {
            tempKafkaConfigFile.delete()
        }
    }

    private fun createTemporaryKafkaConfig(): File {
        // Path to the template config in the main resources of the kafka connector module
        val originalConfigPath = "connectors/kafka/config.sample.yml"
        val tempFile = Files.createTempFile("test-config-kafka", ".yml").toFile()

        var content = File(originalConfigPath).readText()
        content = content.replace(Regex("bootstrapServers:\\s*\".*\""), "bootstrapServers: \"${kafkaContainer.bootstrapServers}\"")
        content = content.replace(Regex("topic:\\s*\".*\""), "topic: \"$KAFKA_TEST_TOPIC\"")
        content = content.replace(Regex("groupId:\\s*\".*\""), "groupId: \"test-kafka-consumer-group-${System.currentTimeMillis()}\"")
        // Ensure other necessary defaults are fine, e.g., autoCommitEnabled, etc.

        tempFile.writeText(content)
        logger.info("Temporary Kafka config created at: ${tempFile.absolutePath} with content:\n$content")
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
            .topicName(PULSAR_OUTPUT_TOPIC) // This is where the source *writes to* in Pulsar.
            .archive("/pulsar/connectors/$narFileName")
            .sourceType(CONNECTOR_TYPE) // From connectors/kafka/connector.yaml
            .parallelism(1)
            // The 'configs' map here should match what the Pulsar IO runtime expects for a connector
            // that uses 'configFile'. The key 'configFile' with the path to the config.kafka.yml is standard.
            .configs(mapOf("configFile" to tempConfigContainerPath))
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
        // This logic assumes the test is run from a context where "connectors/kafka" is a subdirectory
        // or it can find the project root to navigate to "connectors/kafka/build/libs"
        // This might need adjustment based on the actual execution context of the tests by Gradle.
        val projectRoot = File(System.getProperty("user.dir")).parentFile.parentFile // Heuristic
        val connectorBuildDir = File(projectRoot, "connectors/kafka/build/libs")

        logger.info("Attempting to find NAR in directory: ${connectorBuildDir.absolutePath}")
        
        connectorBuildDir.listFiles { _, name -> name.endsWith(".nar") && name.startsWith("pulsar-io-kafka") }?.firstOrNull()?.let {
            logger.info("Found NAR: ${it.absolutePath}")
            return it.absolutePath
        }

        // Fallback for different project structure or execution directory
        val alternativeBuildDir = File("../kafka/build/libs") // If run from connectors/test
         alternativeBuildDir.listFiles { _, name -> name.endsWith(".nar") && name.startsWith("pulsar-io-kafka") }?.firstOrNull()?.let {
            logger.info("Found NAR in alternative location: ${it.absolutePath}")
            return it.absolutePath
        }

        // Another fallback if the current directory *is* the kafka connector module directory
        val currentDirAsModule = File("build/libs")
        currentDirAsModule.listFiles { _, name -> name.endsWith(".nar") && name.startsWith("pulsar-io-kafka") }?.firstOrNull()?.let {
            logger.info("Found NAR in current directory build/libs: ${it.absolutePath}")
            return it.absolutePath
        }
        
        // Final fallback: try relative to assumed project root if tests run from /connectors/test/kafka
        val targetDir = File(System.getProperty("user.dir"), "../../../connectors/kafka/build/libs/")
        targetDir.listFiles { _, name -> name.endsWith(".nar") && name.startsWith("pulsar-io-kafka") }?.firstOrNull()?.let {
            logger.info("Found NAR in target: ${it.absolutePath}")
            return it.absolutePath
        }


        throw IllegalStateException("Connector NAR file (pulsar-io-kafka-*.nar) not found. Searched in standard build locations.")
    }

    @Test
    fun `should receive messages from Kafka topic via Kafka source connector`() {
        val messageCount = 5
        val testMessagePrefix = "kafka-test-payload-"

        // 1. Send messages to Kafka
        for (i in 0 until messageCount) {
            val payload = "$testMessagePrefix$i"
            kafkaProducer.send(ProducerRecord(KAFKA_TEST_TOPIC, "key-$i", payload)).get(10, TimeUnit.SECONDS)
            logger.info("Sent message to Kafka: $payload")
        }
        kafkaProducer.flush()

        // 2. Initialize Pulsar consumer and receive messages
        val receivedMessages = mutableListOf<String>()
        var consumer: Consumer<String>? = null
        try {
            consumer = pulsarClient.newConsumer(Schema.STRING)
                .topic(PULSAR_OUTPUT_TOPIC)
                .subscriptionName("test-kafka-source-subscription-${System.currentTimeMillis()}")
                .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                .subscribe()

            for (i in 0 until messageCount) {
                val msg: Message<String>? = consumer.receive(30, TimeUnit.SECONDS) // Increased timeout
                if (msg != null) {
                    receivedMessages.add(msg.value)
                    logger.info("Received message from Pulsar: ${msg.value} (Key: ${msg.key})")
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
        assertEquals(messageCount, receivedMessages.size, "Should have received all messages sent to Kafka.")
        for (i in 0 until messageCount) {
            assertEquals("$testMessagePrefix$i", receivedMessages[i], "Message content mismatch for message $i.")
        }
        logger.info("Successfully received and verified $messageCount messages from Kafka via Pulsar Kafka source connector.")
    }
}
