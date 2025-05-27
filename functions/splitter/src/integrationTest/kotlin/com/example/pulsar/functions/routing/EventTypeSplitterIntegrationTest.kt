package com.example.pulsar.functions.routing

import com.example.pulsar.common.CommonEvent
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.apache.pulsar.client.admin.PulsarAdmin
import org.apache.pulsar.client.api.Consumer
import org.apache.pulsar.client.api.Producer
import org.apache.pulsar.client.api.PulsarClient
import org.apache.pulsar.client.api.SubscriptionInitialPosition
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle
import org.slf4j.LoggerFactory
import org.testcontainers.containers.PulsarContainer
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.jvm.JvmField

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventTypeSplitterIntegrationTest {

    private val objectMapper = jacksonObjectMapper()
    private lateinit var pulsarClient: PulsarClient
    private lateinit var adminClient: PulsarAdmin // Renamed to avoid conflict with org.apache.pulsar.client.admin.PulsarAdmin

    private val inputTopic = "persistent://public/default/splitter-integration-input"
    private val functionName = "eventTypeSplitterIntegrationTest"

    // Path to the JAR inside the container
    private val containerJarPath = "/pulsar/libs/splitter.jar"

    companion object {
        private val logger = LoggerFactory.getLogger(EventTypeSplitterIntegrationTest::class.java)

        // Define the Pulsar Testcontainer
        // Using a specific, known working version is often better than 'latest' for stability.
        // For this example, we'll stick to a recent version.
        @Container
        @JvmField
        val pulsarContainer: PulsarContainer = PulsarContainer(DockerImageName.parse("apachepulsar/pulsar:3.2.2"))
            .withLogConsumer(Slf4jLogConsumer(logger).withPrefix("PULSAR_CONTAINER"))
            .withCopyToContainer(
                // This path needs to be correct relative to where Gradle executes the tests.
                // Assuming execution from the root project directory:
                MountableFile.forHostPath("functions/splitter/build/libs/splitter.jar"),
                "/pulsar/libs/splitter.jar", // Target path inside the container
            )
        // Enable function worker, not strictly necessary for PulsarContainer if it runs standalone with functions enabled by default
        // .withCommand("bin/pulsar", "standalone", "-nfw", "-nss") // Example if functions need explicit enabling
        // For PulsarContainer, functions are typically available.
    }

    @BeforeAll
    fun setup() {
        // Start the container (Testcontainers manages this if @Container is used, but explicit start can be here if needed)
        // pulsarContainer.start() // Not needed if @Container and @JvmField are used with JUnit 5 Jupiter extension

        pulsarClient = PulsarClient.builder()
            .serviceUrl(pulsarContainer.pulsarBrokerUrl)
            .build()

        adminClient = PulsarAdmin.builder()
            .serviceHttpUrl(pulsarContainer.httpServiceUrl)
            .build()

        // Deploy the function
        logger.info("Deploying function $functionName from JAR $containerJarPath")
        try {
            val execResult = pulsarContainer.execInContainer(
                "bin/pulsar-admin", "functions", "create",
                "--jar", containerJarPath,
                "--className", "com.example.pulsar.functions.routing.EventTypeSplitter",
                "--inputs", inputTopic,
                "--name", functionName,
                "--tenant", "public",
                "--namespace", "default",
                // Note: Output topic is dynamic, so not specified here.
                // If the function had a static output, it would be: --output persistent://public/default/some-output
            )
            logger.info("Function deployment stdout: ${execResult.stdout}")
            logger.info("Function deployment stderr: ${execResult.stderr}")
            if (execResult.exitCode != 0) {
                throw RuntimeException("Failed to deploy function: ${execResult.stderr}")
            }
            logger.info("Function $functionName deployed successfully.")

            // Wait a bit for the function to be fully initialized
            // This can be flaky; a better approach is to check function status via admin API
            Thread.sleep(5000) // Giving 5 seconds for the function to initialize
        } catch (e: Exception) {
            logger.error("Error deploying Pulsar function: ${e.message}", e)
            // Force container logs to be printed for easier debugging
            logger.error("Pulsar Container Logs:\n${pulsarContainer.logs}")
            throw e // Fail fast if deployment doesn't work
        }
    }

    @AfterAll
    fun tearDown() {
        // Clean up resources
        // Undeploy function (optional, as container will be destroyed)
        try {
            adminClient.functions().deleteFunction("public", "default", functionName)
        } catch (e: Exception) {
            logger.warn("Failed to delete function $functionName: ${e.message}", e)
        }

        pulsarClient.close()
        adminClient.close()
        // Container is stopped automatically by Testcontainers JUnit 5 extension
        // pulsarContainer.stop()
    }

    private fun createProducer(topic: String): Producer<ByteArray> {
        return pulsarClient.newProducer()
            .topic(topic)
            .create()
    }

    private fun createConsumer(topic: String, subName: String = "sub-${UUID.randomUUID()}"): Consumer<ByteArray> {
        return pulsarClient.newConsumer()
            .topic(topic)
            .subscriptionName(subName)
            .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
            .ackTimeout(10, TimeUnit.SECONDS) // Configure ack timeout for robust consumption
            .subscribe()
    }

    private fun createCommonEventJson(eventType: String, source: String = "test-integ-source", data: String = "{}"): String {
        val event = CommonEvent(
            eventId = UUID.randomUUID().toString(),
            source = source,
            eventType = eventType,
            timestamp = "2023-01-01T00:00:00Z", // Using a fixed timestamp for consistency
            data = objectMapper.readTree(data),
        )
        return objectMapper.writeValueAsString(event)
    }

    @Test
    fun `should route event to specific event type topic`() {
        val eventType = "ORDER_PLACED"
        val sanitizedEventType = "order_placed" // Manual sanitization for verification
        val expectedOutputTopic = "persistent://public/default/fn-split-$sanitizedEventType"
        val testMessageJson = createCommonEventJson(eventType)

        val producer = createProducer(inputTopic)
        val consumer = createConsumer(expectedOutputTopic)

        producer.send(testMessageJson.toByteArray())
        logger.info("Sent message for event type '$eventType' to input topic '$inputTopic'")

        val receivedMessage = consumer.receive(15, TimeUnit.SECONDS) // Increased timeout

        Assertions.assertNotNull(receivedMessage, "Message should be received from $expectedOutputTopic")
        val receivedEvent = objectMapper.readValue(receivedMessage.data, CommonEvent::class.java)
        Assertions.assertEquals(eventType, receivedEvent.eventType, "Received event type should match")

        consumer.acknowledge(receivedMessage)
        logger.info("Successfully received and acknowledged message from '$expectedOutputTopic'")

        producer.close()
        consumer.close()
    }

    @Test
    fun `should route different event types to respective topics`() {
        val eventType1 = "INVENTORY_UPDATED"
        val sanitizedEventType1 = "inventory_updated"
        val outputTopic1 = "persistent://public/default/fn-split-$sanitizedEventType1"
        val messageJson1 = createCommonEventJson(eventType1)

        val eventType2 = "USER_REGISTERED"
        val sanitizedEventType2 = "user_registered"
        val outputTopic2 = "persistent://public/default/fn-split-$sanitizedEventType2"
        val messageJson2 = createCommonEventJson(eventType2)

        val producer = createProducer(inputTopic)
        val consumer1 = createConsumer(outputTopic1, "sub-$sanitizedEventType1")
        val consumer2 = createConsumer(outputTopic2, "sub-$sanitizedEventType2")

        producer.send(messageJson1.toByteArray())
        logger.info("Sent message for event type '$eventType1'")
        producer.send(messageJson2.toByteArray())
        logger.info("Sent message for event type '$eventType2'")

        // Consume from first topic
        val receivedMessage1 = consumer1.receive(10, TimeUnit.SECONDS)
        Assertions.assertNotNull(receivedMessage1, "Message should be received from $outputTopic1")
        val receivedEvent1 = objectMapper.readValue(receivedMessage1.data, CommonEvent::class.java)
        Assertions.assertEquals(eventType1, receivedEvent1.eventType)
        consumer1.acknowledge(receivedMessage1)
        logger.info("Received and acknowledged message from '$outputTopic1'")

        // Consume from second topic
        val receivedMessage2 = consumer2.receive(10, TimeUnit.SECONDS)
        Assertions.assertNotNull(receivedMessage2, "Message should be received from $outputTopic2")
        val receivedEvent2 = objectMapper.readValue(receivedMessage2.data, CommonEvent::class.java)
        Assertions.assertEquals(eventType2, receivedEvent2.eventType)
        consumer2.acknowledge(receivedMessage2)
        logger.info("Received and acknowledged message from '$outputTopic2'")

        // Ensure no cross-contamination (optional, but good practice)
        val rogueMessage1 = consumer2.receive(2, TimeUnit.SECONDS) // Short timeout
        Assertions.assertNull(rogueMessage1, "Message for $eventType1 should not be on $outputTopic2")
        val rogueMessage2 = consumer1.receive(2, TimeUnit.SECONDS) // Short timeout
        Assertions.assertNull(rogueMessage2, "Message for $eventType2 should not be on $outputTopic1")

        producer.close()
        consumer1.close()
        consumer2.close()
    }

    @Test
    fun `should route event needing sanitization to correctly named topic`() {
        val eventType = "Payment Processed V2!"
        // Expected sanitization: "payment-processed-v2-"
        // The function logic: commonEvent.eventType.lowercase().replace(Regex("[^a-z0-9-]"), "-")
        // "payment processed v2!" -> "payment-processed-v2-" (space becomes -, ! becomes -)
        val sanitizedEventType = "payment-processed-v2-"
        val expectedOutputTopic = "persistent://public/default/fn-split-$sanitizedEventType"
        val testMessageJson = createCommonEventJson(eventType)

        val producer = createProducer(inputTopic)
        val consumer = createConsumer(expectedOutputTopic)

        producer.send(testMessageJson.toByteArray())
        logger.info("Sent message for event type '$eventType'")

        val receivedMessage = consumer.receive(10, TimeUnit.SECONDS)
        Assertions.assertNotNull(receivedMessage, "Message should be received from $expectedOutputTopic")
        val receivedEvent = objectMapper.readValue(receivedMessage.data, CommonEvent::class.java)
        Assertions.assertEquals(eventType, receivedEvent.eventType)

        consumer.acknowledge(receivedMessage)
        logger.info("Received and acknowledged message from '$expectedOutputTopic'")

        producer.close()
        consumer.close()
    }
}
