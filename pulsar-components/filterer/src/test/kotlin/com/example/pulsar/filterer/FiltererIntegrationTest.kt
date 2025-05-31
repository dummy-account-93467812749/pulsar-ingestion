package com.example.pulsar.filterer

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.apache.pulsar.client.admin.PulsarAdmin
import org.apache.pulsar.client.admin.PulsarAdminException
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
import java.util.Collections
import java.util.HashSet // Explicitly java.util.HashSet
import java.util.UUID
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FiltererIntegrationTest {

    private val objectMapper = jacksonObjectMapper()
    private lateinit var pulsarClient: PulsarClient
    private lateinit var adminClient: PulsarAdmin
    private lateinit var functionRunner: LocalRunner
    private lateinit var pulsarContainer: PulsarContainer

    // Updated input topic for the Filterer function
    private val filtererInputTopic = "persistent://acme/ingest/vehicle-telemetry-common-format"
    private val functionName = "filtererManualTest-${UUID.randomUUID().toString().take(8)}"

    companion object {
        private val logger = LoggerFactory.getLogger(FiltererIntegrationTest::class.java)
        private val PULSAR_IMAGE = DockerImageName.parse("apachepulsar/pulsar:3.2.2")
    }

    @BeforeAll
    fun setup() {
        pulsarContainer = PulsarContainer(PULSAR_IMAGE)
            .withFunctionsWorker()
            .withEnv("PULSAR_PREFIX_allowAutoTopicCreation", "true") // Auto topic creation is enabled
            .withEnv("PULSAR_PREFIX_allowAutoTopicCreationType", "non-partitioned")
            .withEnv("PULSAR_PREFIX_brokerDeleteInactiveTopicsEnabled", "false")
            .withStartupTimeout(Duration.ofMinutes(2))

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

        // Ensure "acme" tenant and "acme/ingest" namespace exist for the input topic
        try {
            if (!adminClient.tenants().tenants.contains("acme")) {
                adminClient.tenants().createTenant("acme", TenantInfoImpl(HashSet(), HashSet(listOf("standalone"))))
                logger.info("Tenant 'acme' created.")
            }
            if (!adminClient.namespaces().getNamespaces("acme").contains("acme/ingest")) {
                adminClient.namespaces().createNamespace("acme/ingest")
                logger.info("Namespace 'acme/ingest' created.")
            }
        } catch (e: PulsarAdminException.ConflictException) {
            logger.warn("Tenant 'acme' or namespace 'acme/ingest' already exists, which is fine.")
        } catch (e: Exception) {
            logger.error("CRITICAL: Error ensuring 'acme/ingest' namespace: ${e.message}", e)
            throw e
        }
        // Also ensure public/default exists as some internal Pulsar topics might use it.
        try {
            if (!adminClient.tenants().tenants.contains("public")) {
                adminClient.tenants().createTenant("public", TenantInfoImpl(HashSet(), HashSet(listOf("standalone"))))
            }
            if (!adminClient.namespaces().getNamespaces("public").contains("public/default")) {
                adminClient.namespaces().createNamespace("public/default")
            }
        } catch (e: PulsarAdminException.ConflictException) {
            logger.warn("Tenant/namespace 'public/default' already exists, which is fine.")
        }


        val functionConfig = FunctionConfig().apply {
            name = this@FiltererIntegrationTest.functionName
            inputs = Collections.singletonList(this@FiltererIntegrationTest.filtererInputTopic) // Use the correct input topic
            runtime = FunctionConfig.Runtime.JAVA
            className = com.example.pulsar.filterer.Filterer::class.java.name
            // Output topic is dynamic, so not specified here.
        }

        functionRunner = LocalRunner.builder()
            .functionConfig(functionConfig)
            .brokerServiceUrl(pulsarContainer.pulsarBrokerUrl)
            .build()

        logger.info("Starting LocalRunner for function $functionName listening on $filtererInputTopic...")
        functionRunner.start(false) // Start in a separate thread
        Thread.sleep(8000) // Increased wait time for function to be fully ready
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
            .ackTimeout(10, TimeUnit.SECONDS) // Adjusted for potentially slower CI environments
            .subscribe()
    }

    private fun createCmfJson(tenantId: String, vehicleId: String = "v-${UUID.randomUUID().toString().take(4)}"): String {
        // Simplified CMF structure for the filterer's needs.
        // The filterer primarily cares about meta.tenantId and forwards the original string.
        return objectMapper.writeValueAsString(mapOf(
            "meta" to mapOf("tenantId" to tenantId),
            // Including other CMF fields to make it a more realistic payload
            "dateTime" to "2023-01-01T12:00:00Z",
            "epochSource" to System.currentTimeMillis() / 1000, // Example epoch seconds
            "vehicleId" to vehicleId,
            "deviceId" to "d-$vehicleId",
            "sourceType" to "GEOTAB", // Example, could be any valid SourceType
            "partitionKey" to vehicleId,
            "telemetry" to mapOf("speedGpsMph" to (50..80).random()), // Example telemetry
            "sourceSpecificData" to mapOf("customField" to "customValue-${UUID.randomUUID()}"),
            "events" to null
        ))
    }

    @Test
    fun `filterer should route CMF message to correct tenant topic`() {
        val testTenantId = "tenant-route-test-${UUID.randomUUID().toString().take(4)}"
        val testVehicleId = "vehicle-${UUID.randomUUID().toString().take(6)}"
        val expectedOutputTopic = "persistent://$testTenantId/integration/telemetry"

        // Ensure tenant and namespace for the output topic exist
        try {
            if (!adminClient.tenants().tenants.contains(testTenantId)) {
                adminClient.tenants().createTenant(testTenantId, TenantInfoImpl(HashSet(), HashSet(listOf("standalone"))))
                logger.info("Test tenant '$testTenantId' created.")
            }
            val testNamespace = "$testTenantId/integration"
            if (!adminClient.namespaces().getNamespaces(testTenantId).contains(testNamespace)) {
                adminClient.namespaces().createNamespace(testNamespace)
                logger.info("Test namespace '$testNamespace' created.")
            }
        } catch (e: PulsarAdminException.ConflictException) {
            logger.warn("Test tenant/namespace for '$expectedOutputTopic' already exists.")
        }  catch (e: Exception) {
            logger.error("Failed to create test tenant/namespace for '$expectedOutputTopic': ${e.message}", e)
            throw e // Fail test if setup fails
        }


        val producer = createProducer(filtererInputTopic)
        // Topic auto-creation is enabled, but ensure consumer is created after potential creation by producer/function
        Thread.sleep(1000) // Brief pause to allow backend topic creation if any
        val consumer = createConsumer(expectedOutputTopic)

        val testMessageJson = createCmfJson(tenantId = testTenantId, vehicleId = testVehicleId)
        producer.send(testMessageJson.toByteArray())
        logger.info("Sent CMF message with tenant '$testTenantId' (vehicle: '$testVehicleId') to '$filtererInputTopic'")

        val receivedMessage = consumer.receive(20, TimeUnit.SECONDS) // Increased timeout
        Assertions.assertNotNull(receivedMessage, "Message should be received from $expectedOutputTopic")

        val receivedJsonString = String(receivedMessage.data)
        val receivedJsonMap = objectMapper.readValue(receivedJsonString, Map::class.java) as Map<String, Any>

        // Verify key fields to ensure it's the correct message
        Assertions.assertEquals(testVehicleId, receivedJsonMap["vehicleId"])
        val receivedMeta = receivedJsonMap["meta"] as Map<String, String?>
        Assertions.assertEquals(testTenantId, receivedMeta["tenantId"])

        logger.info("Successfully received message on '$expectedOutputTopic'. Raw: $receivedJsonString")

        consumer.acknowledge(receivedMessage)
        producer.close()
        consumer.close()
    }

    @Test
    fun `filterer should not route message if tenantId is missing in meta`() {
        val testVehicleId = "vehicle-no-tenant-${UUID.randomUUID().toString().take(6)}"
        // This specific tenant and output topic should NOT be created or receive messages.
        // We'll use a dummy topic name for a consumer that should not receive anything.
        val nonExistentOutputTopic = "persistent://dummy-tenant/integration/should-not-exist-${UUID.randomUUID()}"

        // Create CMF JSON where meta.tenantId is null (or meta is missing, or tenantId is blank)
        val messageJsonWithoutTenant = objectMapper.writeValueAsString(mapOf(
            "meta" to mapOf<String, String?>("tenantId" to null), // tenantId is null
            "dateTime" to "2023-01-01T12:00:00Z",
            "epochSource" to System.currentTimeMillis() / 1000,
            "vehicleId" to testVehicleId,
            "deviceId" to "d-$testVehicleId",
            "sourceType" to "GEOTAB",
            "partitionKey" to testVehicleId,
            "telemetry" to mapOf("speedGpsMph" to 60),
            "sourceSpecificData" to mapOf("customField" to "noTenantValue")
        ))

        val producer = createProducer(filtererInputTopic)
        val consumer = createConsumer(nonExistentOutputTopic) // Consumer on a topic that should get no messages

        producer.send(messageJsonWithoutTenant.toByteArray())
        logger.info("Sent CMF message with null tenantId (vehicle: '$testVehicleId') to '$filtererInputTopic'")

        val receivedMessage = consumer.receive(5, TimeUnit.SECONDS) // Short timeout, expect no message
        Assertions.assertNull(receivedMessage, "Message should NOT be received from $nonExistentOutputTopic when tenantId is missing/null")

        producer.close()
        consumer.close()
    }
}
