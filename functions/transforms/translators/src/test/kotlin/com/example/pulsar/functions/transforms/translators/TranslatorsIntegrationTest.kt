package com.example.pulsar.functions.transforms.translators

import com.example.pulsar.common.CommonEvent
import com.fasterxml.jackson.databind.JsonNode // Added import
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.apache.pulsar.client.api.*
import org.apache.pulsar.common.functions.FunctionConfig
import org.apache.pulsar.functions.LocalRunner
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.format.DateTimeParseException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit

class TranslatorsIntegrationTest {

    private lateinit var sharedBrokerLocalRunner: LocalRunner // Renamed for clarity
    private lateinit var client: PulsarClient
    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    private val inputTopicBase = "persistent://public/default/test-input-topic-"
    private val outputTopicBase = "persistent://public/default/test-output-topic-"
    private val functionNameBase = "test-translator-function-"

    @BeforeEach
    fun setupSharedComponents() {
        // This LocalRunner is just to host the broker for the client
        sharedBrokerLocalRunner = LocalRunner.builder()
            .brokerServiceUrl(null) // Use default in-memory broker
            .build()
        sharedBrokerLocalRunner.start(false) 

        client = PulsarClient.builder()
            .serviceUrl(sharedBrokerLocalRunner.brokerServiceUrl)
            .build()
    }

    @AfterEach
    fun teardownSharedComponents() {
        client.close()
        sharedBrokerLocalRunner.stop()
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
        val functionSpecificLocalRunner = LocalRunner.builder()
            .functionConfig(FunctionConfig().apply {
                name = functionName
                inputs = Collections.singletonList(inputTopicName)
                output = outputTopicName
                runtime = FunctionConfig.Runtime.JAVA
                className = functionClass.name
                autoAck = true
            })
            .brokerServiceUrl(sharedBrokerLocalRunner.brokerServiceUrl) // Use the shared broker
            .build()
        
        try {
            functionSpecificLocalRunner.start(false) // Start the function runner

            val producer = client.newProducer(Schema.STRING).topic(inputTopicName).create()
            val consumer = client.newConsumer(Schema.STRING).topic(outputTopicName)
                .subscriptionName("test-sub-${UUID.randomUUID()}").subscribe()

            producer.send(sampleInput)
            TranslatorsIntegrationTest.log.info("Sent message to {}: {}", inputTopicName, sampleInput)

            val msg = consumer.receive(15, TimeUnit.SECONDS) 
            assertNotNull(msg, "Did not receive message from $outputTopicName")

            val outputJson = msg.value
            TranslatorsIntegrationTest.log.info("Received message from {}: {}", outputTopicName, outputJson)
            consumer.acknowledge(msg)

            val commonEvent = objectMapper.readValue(outputJson, CommonEvent::class.java)

            assertEquals(expectedSource, commonEvent.source)
            assertEquals(expectedEventType, commonEvent.eventType)
            assertNotNull(commonEvent.eventId)
            assertTrue(commonEvent.eventId.isNotBlank())

            try {
                java.time.OffsetDateTime.parse(commonEvent.timestamp)
            } catch (e: DateTimeParseException) {
                fail("Timestamp is not in valid ISO 8601 format: ${commonEvent.timestamp}", e)
            }
            
            val originalInputJson = objectMapper.readTree(sampleInput)
            val expectedTimestampFromInput = inputTimestampExtractor(originalInputJson)
            assertEquals(expectedTimestampFromInput, commonEvent.timestamp, "Timestamp mismatch between input and CommonEvent")

            originalInputVerifier(originalInputJson, commonEvent.data)

            producer.close()
            consumer.close()
        } finally {
            functionSpecificLocalRunner.stop()
        }
    }
    
    private val isoFormatter = java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(java.time.ZoneOffset.UTC)
    private fun epochSecondsToISO(epochSeconds: Long): String {
        return java.time.Instant.ofEpochSecond(epochSeconds).atOffset(java.time.ZoneOffset.UTC).format(isoFormatter)
    }

    @Test
    fun testUserProfileTranslator() {
        val uid = 789
        val name = "Test User"
        val created = 1620000000L
        val sampleInput = "{\"uid\": $uid, \"name\": \"$name\", \"created\": $created}" // Corrected
        val topicSuffix = "userprofile-" + UUID.randomUUID().toString().substring(0,8)

        runTestForFunction(
            UserProfileTranslator::class.java,
            inputTopicBase + topicSuffix, outputTopicBase + topicSuffix, functionNameBase + topicSuffix,
            sampleInput,
            "user-service", "USER_PROFILE_EVENT",
            inputTimestampExtractor = { epochSecondsToISO(it.get("created").asLong()) },
            originalInputVerifier = { original, data ->
                assertEquals(original.get("uid").asInt(), data.get("uid").asInt())
                assertEquals(original.get("name").asText(), data.get("name").asText())
            }
        )
    }

    @Test
    fun testOrderRecordTranslator() {
        val orderId = "ORD-INT-001"
        val placedAt = "2024-01-15T11:30:00Z"
        val sampleInput = "{\"orderId\": \"$orderId\", \"items\": [\"itemA\"], \"placedAt\": \"$placedAt\"}" // Corrected
        val topicSuffix = "orderrecord-" + UUID.randomUUID().toString().substring(0,8)

        runTestForFunction(
            OrderRecordTranslator::class.java,
            inputTopicBase + topicSuffix, outputTopicBase + topicSuffix, functionNameBase + topicSuffix,
            sampleInput,
            "order-service", "ORDER_EVENT",
            inputTimestampExtractor = { it.get("placedAt").asText() },
            originalInputVerifier = { original, data ->
                assertEquals(original.get("orderId").asText(), data.get("orderId").asText())
                assertEquals(original.get("items"), data.get("items"))
            }
        )
    }

    @Test
    fun testInventoryUpdateTranslator() {
        val sku = "SKU-INT-123"
        val qty = 150
        val updateTime = 1620050000L
        val sampleInput = "{\"sku\": \"$sku\", \"qty\": $qty, \"updateTime\": $updateTime}" // Corrected
        val topicSuffix = "inventoryupdate-" + UUID.randomUUID().toString().substring(0,8)
        
        runTestForFunction(
            InventoryUpdateTranslator::class.java,
            inputTopicBase + topicSuffix, outputTopicBase + topicSuffix, functionNameBase + topicSuffix,
            sampleInput,
            "inventory-service", "INVENTORY_EVENT",
            inputTimestampExtractor = { epochSecondsToISO(it.get("updateTime").asLong()) },
            originalInputVerifier = { original, data ->
                assertEquals(original.get("sku").asText(), data.get("sku").asText())
                assertEquals(original.get("qty").asInt(), data.get("qty").asInt())
            }
        )
    }

    @Test
    fun testPaymentNoticeTranslator() {
        val txnId = "TXN-INT-002"
        val time = "2024-01-15T12:00:00Z"
        val sampleInput = "{\"txnId\": \"$txnId\", \"amount\": 19.99, \"currency\": \"EUR\", \"time\": \"$time\"}" // Corrected
        val topicSuffix = "paymentnotice-" + UUID.randomUUID().toString().substring(0,8)

        runTestForFunction(
            PaymentNoticeTranslator::class.java,
            inputTopicBase + topicSuffix, outputTopicBase + topicSuffix, functionNameBase + topicSuffix,
            sampleInput,
            "payment-gateway", "PAYMENT_EVENT",
            inputTimestampExtractor = { it.get("time").asText() },
            originalInputVerifier = { original, data ->
                assertEquals(original.get("txnId").asText(), data.get("txnId").asText())
                assertEquals(original.get("amount").asDouble(), data.get("amount").asDouble())
            }
        )
    }

    @Test
    fun testShipmentStatusTranslator() {
        val shipId = "SHIP-INT-321"
        val status = "SHIPPED"
        val deliveredAt = 1620100000L
        val sampleInput = "{\"shipId\": \"$shipId\", \"status\": \"$status\", \"deliveredAt\": $deliveredAt}" // Corrected
        val topicSuffix = "shipmentstatus-" + UUID.randomUUID().toString().substring(0,8)

        runTestForFunction(
            ShipmentStatusTranslator::class.java,
            inputTopicBase + topicSuffix, outputTopicBase + topicSuffix, functionNameBase + topicSuffix,
            sampleInput,
            "shipping-service", "SHIPMENT_EVENT",
            inputTimestampExtractor = { epochSecondsToISO(it.get("deliveredAt").asLong()) },
            originalInputVerifier = { original, data ->
                assertEquals(original.get("shipId").asText(), data.get("shipId").asText())
                assertEquals(original.get("status").asText(), data.get("status").asText())
            }
        )
    }
    
    companion object {
        // Ensure JsonNode is imported if not already
        // import com.fasterxml.jackson.databind.JsonNode 
        private val log = org.slf4j.LoggerFactory.getLogger(TranslatorsIntegrationTest::class.java)
    }
}
