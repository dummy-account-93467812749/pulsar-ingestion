package com.example.pulsar.functions.transforms.translators

import com.example.pulsar.common.CommonEvent
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.apache.pulsar.client.admin.PulsarAdmin
import org.apache.pulsar.client.admin.PulsarAdminException
import org.apache.pulsar.client.api.*
import org.apache.pulsar.common.functions.FunctionConfig
import org.apache.pulsar.common.policies.data.TenantInfoImpl
import org.apache.pulsar.functions.LocalRunner
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
// import org.slf4j.LoggerFactory // Removed
import org.testcontainers.containers.PulsarContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.time.ZoneOffset
import java.util.*
import java.util.concurrent.TimeUnit

class TranslatorsIntegrationTest {
    companion object {
        // private val LOG = LoggerFactory.getLogger(TranslatorsIntegrationTest::class.java) // Removed

        @BeforeAll
        @JvmStatic
        fun dumpCirceChecksumDiagnostics() {
            // println("==== Circe Checksum Diagnostics ====") // Removed
            try {
                val circeCls = com.scurrilous.circe.checksum.Crc32cIntChecksum::class.java
                // println("Crc32cIntChecksum loaded from: " + // Removed
                //         circeCls.protectionDomain.codeSource.location) // Removed
                try {
                    circeCls.getDeclaredMethod("computeChecksum", io.netty.buffer.ByteBuf::class.java)
                    // println("✓ Found computeChecksum(io.netty.buffer.ByteBuf)") // Removed
                } catch (e: NoSuchMethodException) {
                    // println("✘ No computeChecksum(io.netty.buffer.ByteBuf) on Crc32cIntChecksum!") // Removed
                }
            } catch (e: Throwable) { // Catch Throwable for NoClassDefFoundError etc.
                // println("Error during Circe diagnostics: ${e.javaClass.name} - ${e.message}") // Removed
            }
            // println("==== End Circe Checksum Diagnostics ====") // Removed
        }


        private val ISO_FORMATTER =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC)

        private fun epochSecondsToISO(epochSeconds: Long): String =
            java.time.Instant.ofEpochSecond(epochSeconds)
                .atOffset(ZoneOffset.UTC)
                .format(ISO_FORMATTER)

        private val PULSAR_IMAGE = DockerImageName.parse("apachepulsar/pulsar:4.0.5")
    }

    private lateinit var pulsar: PulsarContainer
    private lateinit var client: PulsarClient
    private lateinit var admin: PulsarAdmin
    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    private val inputTopicBase = "persistent://public/default/test-input-topic-"
    private val outputTopicBase = "persistent://public/default/test-output-topic-"
    private val functionNameBase = "test-translator-function-"

    @BeforeEach
    fun setupSharedComponents() {
        pulsar = PulsarContainer(PULSAR_IMAGE)
            .withFunctionsWorker()
            .withEnv("PULSAR_MEM", "-Xms512m -Xmx512m -XX:MaxDirectMemorySize=512m")
            .withEnv("PULSAR_PREFIX_allowAutoTopicCreation", "true")
            .withEnv("PULSAR_PREFIX_allowAutoTopicCreationType", "non-partitioned")
            .withEnv("PULSAR_PREFIX_brokerDeleteInactiveTopicsEnabled", "false")
            .withStartupTimeout(Duration.ofMinutes(2))

        try {
            pulsar.start()
        } catch (e: Exception) {
            // LOG.error("CRITICAL: Failed to start Pulsar container: {}", e.message, e) // Removed
            // if (::pulsar.isInitialized) { // Removed
            //     try { LOG.error("Pulsar container logs on startup failure:\n{}", pulsar.logs) } catch (_: Exception) {} // Removed
            // } // Removed
            throw e
        }

        client = PulsarClient.builder().serviceUrl(pulsar.pulsarBrokerUrl).build()
        admin = PulsarAdmin.builder().serviceHttpUrl(pulsar.httpServiceUrl).build()

        try {
            if (!admin.tenants().tenants.contains("public")) {
                admin.tenants().createTenant("public", TenantInfoImpl(HashSet(), HashSet(listOf("standalone"))))
            }
            if (!admin.namespaces().getNamespaces("public").contains("public/default")) {
                admin.namespaces().createNamespace("public/default")
            }
        } catch (e: PulsarAdminException.ConflictException) {
            // Expected if already exists
        } catch (e: Exception) {
            // LOG.error("CRITICAL: Error ensuring 'public/default' namespace: {}", e.message, e) // Removed
            throw e // Re-throw to fail the test setup
        }
    }

    @AfterEach
    fun teardownSharedComponents() {
        try { admin.close() } catch (e: Exception) { /* LOG.warn("Warn: Error closing admin: {}", e.message) */ } // Removed
        try { client.close() } catch (e: Exception) { /* LOG.warn("Warn: Error closing client: {}", e.message) */ } // Removed
        try {
            if (::pulsar.isInitialized && pulsar.isRunning) {
                pulsar.stop()
            }
        } catch (e: Exception) { /* LOG.warn("Warn: Error stopping Pulsar: {}", e.message) */ } // Removed
    }

    private fun runTestForFunction(
        functionClass: Class<out Any>,
        inputTopicName: String,
        outputTopicName: String,
        functionName: String,
        sampleInput: String,
        expectedSource: String,
        expectedEventType: String,
        inputTimestampExtractor: (JsonNode) -> String,
        originalInputVerifier: (JsonNode, JsonNode) -> Unit
    ) {
        // System.out.println("---- Test: $functionName ----") // Removed

        try {
             if (!admin.topics().getList("public/default").contains(inputTopicName)) {
                admin.topics().createNonPartitionedTopic(inputTopicName)
             }
             if (!admin.topics().getList("public/default").contains(outputTopicName)) {
                admin.topics().createNonPartitionedTopic(outputTopicName)
             }
        } catch (e: PulsarAdminException.ConflictException) {
            // Expected
        } catch (e: Exception) {
            // LOG.warn("[{}] Warn: Non-critical error explicitly creating topics: {}", functionName, e.message) // Removed
        }

        val functionRunner = LocalRunner.builder()
            .functionConfig(FunctionConfig().apply {
                name = functionName
                inputs = Collections.singletonList(inputTopicName)
                output = outputTopicName
                runtime = FunctionConfig.Runtime.JAVA
                className = functionClass.name
                setAutoAck(true)
            })
            .brokerServiceUrl(pulsar.pulsarBrokerUrl)
            .build()

        var producer: Producer<String>? = null
        var consumer: Consumer<String>? = null

        try {
            functionRunner.start(false)
            Thread.sleep(15_000) // Critical wait

            producer = client.newProducer(Schema.STRING)
                .topic(inputTopicName)
                .blockIfQueueFull(true)
                .sendTimeout(30, TimeUnit.SECONDS)
                .create()

            consumer = client.newConsumer(Schema.STRING)
                .topic(outputTopicName)
                .subscriptionName("test-sub-${UUID.randomUUID()}")
                .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                .subscribe()

            try {
                producer.send(sampleInput)
            } catch (e: PulsarClientException) {
                // LOG.error("CRITICAL [{}] Send failed to {}: {}", functionName, inputTopicName, e.message) // Removed
                // try { LOG.error("Pulsar container logs for [{}] at send failure:\n{}", functionName, pulsar.logs) } catch (logEx: Exception) { // Removed
                //     LOG.error("Failed to get Pulsar container logs: {}", logEx.message) // Removed
                // } // Removed
                throw e
            }

            val msg = consumer.receive(30, TimeUnit.SECONDS)
            assertNotNull(msg, "[$functionName] Did not receive message from $outputTopicName")

            val commonEvent = objectMapper.readValue(msg.value, CommonEvent::class.java)
            assertEquals(expectedSource, commonEvent.source)
            assertEquals(expectedEventType, commonEvent.eventType)
            assertTrue(commonEvent.eventId.isNotBlank())
            assertDoesNotThrow { java.time.OffsetDateTime.parse(commonEvent.timestamp) }

            val originalJson = objectMapper.readTree(sampleInput)
            assertEquals(inputTimestampExtractor(originalJson), commonEvent.timestamp)
            originalInputVerifier(originalJson, commonEvent.data)

        } catch (e: Exception) {
            // LOG.error("CRITICAL [{}] Test failed: {}", functionName, e.message) // Removed
            // if (e !is PulsarClientException && ::pulsar.isInitialized && pulsar.isRunning) { // Removed
            //      try { LOG.error("Pulsar container logs for [{}] at general failure:\n{}", functionName, pulsar.logs) } catch (logEx: Exception) { // Removed
            //         LOG.error("Failed to get Pulsar container logs: {}", logEx.message) // Removed
            //     } // Removed
            // } // Removed
            fail("[$functionName] Test failed: ${e.message}")
        } finally {
            try { producer?.close() } catch (e: Exception) { /* LOG.warn("[{}] Warn: Error closing producer", functionName) */ } // Removed
            try { consumer?.close() } catch (e: Exception) { /* LOG.warn("[{}] Warn: Error closing consumer", functionName) */ } // Removed
            try { functionRunner.stop() } catch (e: Exception) { /* LOG.warn("[{}] Warn: Error stopping function runner", functionName) */ } // Removed
        }
    }

    // --- Test methods (UserProfileTranslator, OrderRecordTranslator, etc.) ---
    @Test fun testUserProfileTranslator() {
        val uid     = 789
        val name    = "Test User"
        val created = 1_620_000_000L
        val sample  = """{"uid":$uid,"name":"$name","created":$created}"""
        val suffix  = "userprofile-" + UUID.randomUUID().toString().take(8)

        runTestForFunction(
            UserProfileTranslator::class.java,
            inputTopicBase + suffix,
            outputTopicBase + suffix,
            functionNameBase + suffix,
            sample,
            "user-service",
            "USER_PROFILE_EVENT",
            { epochSecondsToISO(it.get("created").asLong()) },
            { orig, data ->
                assertEquals(orig.get("uid").asInt(),   data.get("uid").asInt())
                assertEquals(orig.get("name").asText(), data.get("name").asText())
            }
        )
    }

    @Test fun testOrderRecordTranslator() {
        val orderId  = "ORD-INT-001"
        val placedAt = "2024-01-15T11:30:00Z"
        val sample   = """{"orderId":"$orderId","items":["itemA"],"placedAt":"$placedAt"}"""
        val suffix   = "orderrecord-" + UUID.randomUUID().toString().take(8)

        runTestForFunction(
            OrderRecordTranslator::class.java,
            inputTopicBase + suffix,
            outputTopicBase + suffix,
            functionNameBase + suffix,
            sample,
            "order-service",
            "ORDER_EVENT",
            { it.get("placedAt").asText() },
            { orig, data ->
                assertEquals(orig.get("orderId").asText(), data.get("orderId").asText())
                assertEquals(orig.get("items"),             data.get("items"))
            }
        )
    }

    @Test fun testInventoryUpdateTranslator() {
        val sku        = "SKU-INT-123"
        val qty        = 150
        val updateTime = 1_620_050_000L
        val sample     = """{"sku":"$sku","qty":$qty,"updateTime":$updateTime}"""
        val suffix     = "inventoryupdate-" + UUID.randomUUID().toString().take(8)

        runTestForFunction(
            InventoryUpdateTranslator::class.java,
            inputTopicBase + suffix,
            outputTopicBase + suffix,
            functionNameBase + suffix,
            sample,
            "inventory-service",
            "INVENTORY_EVENT",
            { epochSecondsToISO(it.get("updateTime").asLong()) },
            { orig, data ->
                assertEquals(orig.get("sku").asText(), data.get("sku").asText())
                assertEquals(orig.get("qty").asInt(),  data.get("qty").asInt())
            }
        )
    }

    @Test fun testPaymentNoticeTranslator() {
        val txnId    = "TXN-INT-002"
        val time     = "2024-01-15T12:00:00Z"
        val sample   = """{"txnId":"$txnId","amount":19.99,"currency":"EUR","time":"$time"}"""
        val suffix   = "paymentnotice-" + UUID.randomUUID().toString().take(8)

        runTestForFunction(
            PaymentNoticeTranslator::class.java,
            inputTopicBase + suffix,
            outputTopicBase + suffix,
            functionNameBase + suffix,
            sample,
            "payment-gateway",
            "PAYMENT_EVENT",
            { it.get("time").asText() },
            { orig, data ->
                assertEquals(orig.get("txnId").asText(),  data.get("txnId").asText())
                assertEquals(orig.get("amount").asDouble(), data.get("amount").asDouble())
            }
        )
    }

    @Test fun testShipmentStatusTranslator() {
        val shipId      = "SHIP-INT-321"
        val status      = "SHIPPED"
        val deliveredAt = 1_620_100_000L
        val sample      = """{"shipId":"$shipId","status":"$status","deliveredAt":$deliveredAt}"""
        val suffix      = "shipmentstatus-" + UUID.randomUUID().toString().take(8)

        runTestForFunction(
            ShipmentStatusTranslator::class.java,
            inputTopicBase + suffix,
            outputTopicBase + suffix,
            functionNameBase + suffix,
            sample,
            "shipping-service",
            "SHIPMENT_EVENT",
            { epochSecondsToISO(it.get("deliveredAt").asLong()) },
            { orig, data ->
                assertEquals(orig.get("shipId").asText(), data.get("shipId").asText())
                assertEquals(orig.get("status").asText(), data.get("status").asText())
            }
        )
    }
}