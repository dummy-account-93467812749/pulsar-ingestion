package com.example.pulsar.filterer

import com.example.pulsar.libs.CommonEvent
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.apache.pulsar.client.admin.PulsarAdmin
import org.apache.pulsar.client.admin.PulsarAdminException // For ConflictException
import org.apache.pulsar.client.api.Consumer
import org.apache.pulsar.client.api.Producer
import org.apache.pulsar.client.api.PulsarClient
import org.apache.pulsar.client.api.SubscriptionInitialPosition
import org.apache.pulsar.common.functions.FunctionConfig
import org.apache.pulsar.common.policies.data.TenantInfoImpl
import org.apache.pulsar.functions.LocalRunner
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import org.testcontainers.containers.PulsarContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.Collections // Used for Collections.singletonList
import java.util.HashSet // Used for new TenantInfoImpl(HashSet(), HashSet(...))
import java.util.UUID // Used for UUID.randomUUID()
import java.util.concurrent.TimeUnit
// REMOVED: import kotlin.collections.HashSet // This was causing ambiguity and was unused

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventTypeSplitterIntegrationTest {

    private val objectMapper = jacksonObjectMapper()
    private lateinit var pulsarClient: PulsarClient
    private lateinit var adminClient: PulsarAdmin
    private lateinit var functionRunner: LocalRunner
    private lateinit var pulsarContainer: PulsarContainer // Manually managed

    private val inputTopic = "persistent://public/default/splitter-manual-input-${UUID.randomUUID().toString().take(8)}"
    private val functionName = "eventTypeSplitterManualTest-${UUID.randomUUID().toString().take(8)}"

    companion object {
        private val logger = LoggerFactory.getLogger(EventTypeSplitterIntegrationTest::class.java)

        // Define the Pulsar Testcontainer image
        private val PULSAR_IMAGE = DockerImageName.parse("apachepulsar/pulsar:3.2.2") // Or your preferred version
    }

    @BeforeAll
    fun setup() {
        pulsarContainer = PulsarContainer(PULSAR_IMAGE)
            .withFunctionsWorker()
            .withEnv("PULSAR_PREFIX_allowAutoTopicCreation", "true")
            .withEnv("PULSAR_PREFIX_allowAutoTopicCreationType", "non-partitioned")
            .withEnv("PULSAR_PREFIX_brokerDeleteInactiveTopicsEnabled", "false") // Similar to translator
            .withStartupTimeout(Duration.ofMinutes(2)) // Similar to translator

        try {
            logger.info("Starting PulsarContainer manually...")
            pulsarContainer.start()
            logger.info("PulsarContainer started.")
        } catch (e: Exception) {
            logger.error("Failed to start PulsarContainer: ${e.message}", e)
            if (::pulsarContainer.isInitialized && pulsarContainer.logs != null) {
                System.err.println("Pulsar container logs on startup failure:\n${pulsarContainer.logs}")
            }
            throw e
        }

        pulsarClient = PulsarClient.builder()
            .serviceUrl(pulsarContainer.pulsarBrokerUrl)
            .build()

        adminClient = PulsarAdmin.builder()
            .serviceHttpUrl(pulsarContainer.httpServiceUrl)
            .build()

        try {
            if (!adminClient.tenants().tenants.contains("public")) {
                // HashSet() now unambiguously refers to java.util.HashSet
                adminClient.tenants().createTenant("public", TenantInfoImpl(HashSet(), HashSet(listOf("standalone"))))
            }
            if (!adminClient.namespaces().getNamespaces("public").contains("public/default")) {
                adminClient.namespaces().createNamespace("public/default")
            }
        } catch (e: PulsarAdminException.ConflictException) {
            logger.warn("Tenant/namespace 'public/default' already exists, which is fine.")
        } catch (e: Exception) {
            logger.error("CRITICAL: Error ensuring 'public/default' namespace: ${e.message}", e)
            throw e
        }

        val functionConfig = FunctionConfig().apply {
            name = this@EventTypeSplitterIntegrationTest.functionName
            inputs = Collections.singletonList(this@EventTypeSplitterIntegrationTest.inputTopic)
            runtime = FunctionConfig.Runtime.JAVA
            className = com.example.pulsar.filterer.EventTypeSplitter::class.java.name
            // autoAck = true // Default
        }

        functionRunner = LocalRunner.builder()
            .functionConfig(functionConfig)
            .brokerServiceUrl(pulsarContainer.pulsarBrokerUrl)
            .build()

        logger.info("Starting LocalRunner for function $functionName...")
        functionRunner.start(false) // Start in a separate thread
        Thread.sleep(5000) // Give function time to initialize
        logger.info("LocalRunner for function $functionName started.")
    }

    @AfterAll
    fun tearDown() {
        logger.info("Stopping LocalRunner for function $functionName...")
        try {
            functionRunner.stop()
            logger.info("LocalRunner for function $functionName stopped.")
        } catch (e: Exception) {
            logger.error("Error stopping LocalRunner: ${e.message}", e)
        }

        try { adminClient.close() } catch (e: Exception) { logger.warn("Error closing admin client: ${e.message}", e) }
        try { pulsarClient.close() } catch (e: Exception) { logger.warn("Error closing pulsar client: ${e.message}", e) }

        try {
            if (::pulsarContainer.isInitialized && pulsarContainer.isRunning) {
                logger.info("Stopping PulsarContainer manually...")
                pulsarContainer.stop()
                logger.info("PulsarContainer stopped.")
            }
        } catch (e: Exception) {
            logger.warn("Error stopping PulsarContainer: ${e.message}", e)
        }
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
            .ackTimeout(10, TimeUnit.SECONDS)
            .subscribe()
    }

    private fun createCommonEventJson(eventType: String, source: String = "test-integ-source", data: String = "{}"): String {
        val event = CommonEvent(
            eventId = UUID.randomUUID().toString(),
            source = source,
            eventType = eventType,
            timestamp = "2023-01-01T00:00:00Z",
            data = objectMapper.readTree(data),
        )
        return objectMapper.writeValueAsString(event)
    }

    @Test
    fun `should route event to specific event type topic`() {
        val eventType = "ORDER_PLACED"
        val sanitizedEventType = "order-placed"
        val expectedOutputTopic = "persistent://public/default/fn-split-$sanitizedEventType"
        val testMessageJson = createCommonEventJson(eventType)

        val producer = createProducer(inputTopic)
        val consumer = createConsumer(expectedOutputTopic)

        producer.send(testMessageJson.toByteArray())
        logger.info("Sent message for event type '$eventType' to input topic '$inputTopic'")

        val receivedMessage = consumer.receive(15, TimeUnit.SECONDS)

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
        val sanitizedEventType1 = "inventory-updated"
        val outputTopic1 = "persistent://public/default/fn-split-$sanitizedEventType1"
        val messageJson1 = createCommonEventJson(eventType1)

        val eventType2 = "USER_REGISTERED"
        val sanitizedEventType2 = "user-registered"
        val outputTopic2 = "persistent://public/default/fn-split-$sanitizedEventType2"
        val messageJson2 = createCommonEventJson(eventType2)

        val producer = createProducer(inputTopic)
        val consumer1 = createConsumer(outputTopic1, "sub-$sanitizedEventType1")
        val consumer2 = createConsumer(outputTopic2, "sub-$sanitizedEventType2")

        producer.send(messageJson1.toByteArray())
        logger.info("Sent message for event type '$eventType1'")
        producer.send(messageJson2.toByteArray())
        logger.info("Sent message for event type '$eventType2'")

        val receivedMessage1 = consumer1.receive(10, TimeUnit.SECONDS)
        Assertions.assertNotNull(receivedMessage1, "Message should be received from $outputTopic1")
        val receivedEvent1 = objectMapper.readValue(receivedMessage1.data, CommonEvent::class.java)
        Assertions.assertEquals(eventType1, receivedEvent1.eventType)
        consumer1.acknowledge(receivedMessage1)

        val receivedMessage2 = consumer2.receive(10, TimeUnit.SECONDS)
        Assertions.assertNotNull(receivedMessage2, "Message should be received from $outputTopic2")
        val receivedEvent2 = objectMapper.readValue(receivedMessage2.data, CommonEvent::class.java)
        Assertions.assertEquals(eventType2, receivedEvent2.eventType)
        consumer2.acknowledge(receivedMessage2)

        producer.close()
        consumer1.close()
        consumer2.close()
    }

    @Test
    fun `should route event needing sanitization to correctly named topic`() {
        val eventType = "Payment Processed V2!"
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

        producer.close()
        consumer.close()
    }
}
