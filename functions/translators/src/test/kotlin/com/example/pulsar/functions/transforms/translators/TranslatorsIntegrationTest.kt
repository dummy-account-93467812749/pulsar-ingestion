package com.example.pulsar.functions.transforms.translators
import com.example.pulsar.common.CommonEvent
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.apache.pulsar.client.api.*
import org.apache.pulsar.common.functions.FunctionConfig
import org.apache.pulsar.functions.LocalRunner
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.testcontainers.containers.PulsarContainer
import java.time.format.DateTimeFormatter
import java.time.ZoneOffset
import java.util.*
import java.util.concurrent.TimeUnit
    class TranslatorsIntegrationTest {
    companion object {
        private val isoFormatter = 
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC)

        private fun epochSecondsToISO(epochSeconds: Long): String =
            java.time.Instant.ofEpochSecond(epochSeconds)
                .atOffset(ZoneOffset.UTC)
                .format(isoFormatter)
    }

    private lateinit var pulsar: PulsarContainer
    private lateinit var client: PulsarClient
    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    private val inputTopicBase  = "persistent://public/default/test-input-topic-"
    private val outputTopicBase = "persistent://public/default/test-output-topic-"
    private val functionNameBase = "test-translator-function-"

    @BeforeEach
    fun setupSharedComponents() {
        // start a real Pulsar broker + functions worker
        pulsar = PulsarContainer("4.0.4")
            .withFunctionsWorker()
        pulsar.start()

        client = PulsarClient.builder()
            .serviceUrl(pulsar.pulsarBrokerUrl)
            .build()
    }

    @AfterEach
    fun teardownSharedComponents() {
        client.close()
        pulsar.stop()
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
        val functionRunner = LocalRunner.builder()
            .functionConfig(FunctionConfig().apply {
                name = functionName
                inputs = Collections.singletonList(inputTopicName)
                output = outputTopicName
                runtime = FunctionConfig.Runtime.JAVA
                className = functionClass.name
                autoAck = true
            })
            .brokerServiceUrl(pulsar.pulsarBrokerUrl)
            .build()

        try {
            functionRunner.start(false)

            val producer: Producer<String> = client
                .newProducer<String>(Schema.STRING)
                .topic(inputTopicName)
                .create()

            val consumer: Consumer<String> = client
                .newConsumer<String>(Schema.STRING)
                .topic(outputTopicName)
                .subscriptionName("test-sub-${UUID.randomUUID()}")
                .subscribe()

            // send & receive
            producer.send(sampleInput)
            val msg = consumer.receive(15, TimeUnit.SECONDS)
            assertNotNull(msg, "Did not receive message from $outputTopicName")
            consumer.acknowledge(msg)

            // parse into your CommonEvent
            val commonEvent = objectMapper.readValue(msg.value, CommonEvent::class.java)

            // basic assertions
            assertEquals(expectedSource,    commonEvent.source)
            assertEquals(expectedEventType, commonEvent.eventType)
            assertTrue(commonEvent.eventId.isNotBlank())
            // timestamp is valid ISO‑8601
            assertDoesNotThrow {
                java.time.OffsetDateTime.parse(commonEvent.timestamp)
            }

            // timestamp matches input
            val originalJson = objectMapper.readTree(sampleInput)
            assertEquals(
                inputTimestampExtractor(originalJson),
                commonEvent.timestamp,
                "Timestamp mismatch"
            )

            // delegate the rest of the assertions to the lambda
            originalInputVerifier(originalJson, commonEvent.data)

            producer.close()
            consumer.close()
        } finally {
            functionRunner.stop()
        }
    }

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