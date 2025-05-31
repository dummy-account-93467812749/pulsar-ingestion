package com.example.pulsar.functions.transforms.translators // Corrected package

import com.example.pulsar.functions.cmf.OrderRecordTranslator // Keep existing non-CMF translators for now
import com.example.pulsar.functions.cmf.InventoryUpdateTranslator
import com.example.pulsar.functions.cmf.PaymentNoticeTranslator
import com.example.pulsar.functions.cmf.ShipmentStatusTranslator
import com.example.pulsar.functions.cmf.UserProfileTranslator
// import com.example.pulsar.cmf.format.CommonMessageFormat // TODO: Will need this for typed deserialization if we go that route
import com.example.pulsar.functions.cmf.translators.GeotabTranslator
import com.example.pulsar.functions.cmf.translators.CalAmpTranslator
import com.example.pulsar.functions.cmf.translators.FordTranslator
// import com.example.pulsar.cmf.format.geotab.GeotabInputMessage // TODO: And this
// import com.example.pulsar.cmf.format.calamp.CalAmpInputMessage // TODO: And this
// import com.example.pulsar.cmf.format.ford.FordInputMessage // TODO: And this
import com.example.pulsar.libs.CommonEvent
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.apache.pulsar.client.admin.PulsarAdmin
import org.apache.pulsar.client.admin.PulsarAdminException
import org.apache.pulsar.client.api.Consumer
import org.apache.pulsar.client.api.Producer
import org.apache.pulsar.client.api.PulsarClient
import org.apache.pulsar.client.api.PulsarClientException
import org.apache.pulsar.client.api.Schema
import org.apache.pulsar.client.api.SubscriptionInitialPosition
import org.apache.pulsar.common.functions.FunctionConfig
import org.apache.pulsar.common.policies.data.TenantInfoImpl
import org.apache.pulsar.functions.LocalRunner
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PulsarContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.HashSet
import java.util.UUID
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS) // Key change for sharing resources
class TranslatorsIntegrationTest {
    companion object {
        // Circe checksum diagnostics can remain if useful for debugging, otherwise remove
        // @BeforeAll // This companion object BeforeAll will run before the class instance @BeforeAll
        // @JvmStatic
        // fun dumpCirceChecksumDiagnostics() { ... }

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
    private val objectMapper: ObjectMapper = jacksonObjectMapper() // Already present, good.

    // Bases for old tests - keep them for non-CMF tests
    private val inputTopicBaseOld = "persistent://public/default/test-input-topic-"
    private val outputTopicBaseOld = "persistent://public/default/test-output-topic-"
    private val functionNameBaseOld = "test-translator-function-"
    // New base for CMF function names, input topics for CMF tests will be more specific
    private val cmfFunctionNameBase = "test-cmf-translator-function-"


    @BeforeAll
    fun startPulsarAndClients() { // Renamed from setupSharedComponents
        pulsar = PulsarContainer(PULSAR_IMAGE)
            .withFunctionsWorker()
            .withEnv("PULSAR_MEM", "-Xms512m -Xmx512m -XX:MaxDirectMemorySize=512m")
            .withEnv("PULSAR_PREFIX_allowAutoTopicCreation", "true")
            .withEnv("PULSAR_PREFIX_allowAutoTopicCreationType", "non-partitioned")
            .withEnv("PULSAR_PREFIX_brokerDeleteInactiveTopicsEnabled", "false")
            .withStartupTimeout(Duration.ofMinutes(2)) // Increased timeout just in case, can be tuned

        try {
            pulsar.start()
        } catch (e: Exception) {
            // Consider adding logging back if you encounter issues
            // if (::pulsar.isInitialized && pulsar.logs != null) {
            //     System.err.println("Pulsar container logs on startup failure:\n${pulsar.logs}")
            // }
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
            // System.err.println("CRITICAL: Error ensuring 'public/default' namespace: ${e.message}")
            throw e // Re-throw to fail the test setup
        }
    }

    @AfterAll
    fun stopPulsarAndClients() { // Renamed from teardownSharedComponents
        try { admin.close() } catch (e: Exception) { /* System.err.println("Warn: Error closing admin: ${e.message}") */ }
        try { client.close() } catch (e: Exception) { /* System.err.println("Warn: Error closing client: ${e.message}") */ }
        try {
            if (::pulsar.isInitialized && pulsar.isRunning) {
                pulsar.stop()
            }
        } catch (e: Exception) { /* System.err.println("Warn: Error stopping Pulsar: ${e.message}") */ }
    }

    // @BeforeEach and @AfterEach are no longer strictly needed for pulsar/client/admin
    // If you had other per-test setup/teardown, it would go here.

    private fun runTestForFunction(
        functionClass: Class<out Any>,
        inputTopicName: String,
        outputTopicName: String,
        functionName: String,
        sampleInput: String,
        expectedSource: String,
        expectedEventType: String,
        inputTimestampExtractor: (JsonNode) -> String,
        originalInputVerifier: (JsonNode, JsonNode) -> Unit,
    ) {
        // System.out.println("---- Test: $functionName ----")

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
            // System.err.println("[$functionName] Warn: Non-critical error explicitly creating topics: ${e.message}")
        }

        val functionConfig = FunctionConfig().apply {
            name = functionName
            inputs = Collections.singletonList(inputTopicName)
            output = outputTopicName
            runtime = FunctionConfig.Runtime.JAVA
            className = functionClass.name
            // setAutoAck(true) // DEPRECATION REMOVED: This is the default behavior
        }

        // For more robust function readiness check instead of sleep:
        // You could deploy the function via PulsarAdmin API and then poll its status.
        // admin.functions().createFunction(functionConfig, null /* path to jar, not needed for LocalRunner with classname */)
        // Then loop with admin.functions().getFunctionStatus("public", "default", functionName)
        // until status.numRunning > 0 or status.instances.any { it.status.running }

        val functionRunner = LocalRunner.builder()
            .functionConfig(functionConfig)
            .brokerServiceUrl(pulsar.pulsarBrokerUrl)
            // Optional: If your functions have specific NAR files or dependencies:
            // .narPath("/path/to/your/function.nar") // If you package as NAR
            // .userCodeClassLoader(this.javaClass.classLoader) // May help with classloading in some IDEs
            .build()

        var producer: Producer<String>? = null
        var consumer: Consumer<String>? = null // This consumer is for the outputTopicName

        try {
            functionRunner.start(false) // Starts in a separate thread

            // Wait for function to initialize.
            // Reduced sleep. Test and adjust.
            // A more robust way is to poll function status via admin API if this is flaky.
            Thread.sleep(5_000) // CRITICAL WAIT - REDUCED

            producer = client.newProducer(Schema.STRING)
                .topic(inputTopicName)
                .blockIfQueueFull(true)
                .sendTimeout(30, TimeUnit.SECONDS)
                .enableBatching(false) // Disable batching for faster single message delivery in tests
                .create()

            consumer = client.newConsumer(Schema.STRING)
                .topic(outputTopicName)
                .subscriptionName("test-sub-${UUID.randomUUID()}")
                .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                .subscribe()

            try {
                producer.send(sampleInput)
                producer.flush() // Ensure message is sent immediately
            } catch (e: PulsarClientException) {
                // System.err.println("CRITICAL [$functionName] Send failed to $inputTopicName: ${e.message}")
                // try {
                //     System.err.println("Pulsar container logs for [$functionName] at send failure:\n${pulsar.logs}")
                // } catch (logEx: Exception) {
                //     System.err.println("Failed to get Pulsar container logs: ${logEx.message}")
                // }
                throw e
            }

            val msg = consumer.receive(30, TimeUnit.SECONDS) // Timeout for receive
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
            // System.err.println("CRITICAL [$functionName] Test failed: ${e.message}")
            // if (e !is PulsarClientException && ::pulsar.isInitialized && pulsar.isRunning) {
            //      try {
            //          System.err.println("Pulsar container logs for [$functionName] at general failure:\n${pulsar.logs}")
            //      } catch (logEx: Exception) {
            //         System.err.println("Failed to get Pulsar container logs: ${logEx.message}")
            //     }
            // }
            fail("[$functionName] Test failed: ${e.message}", e) // Add cause for better debugging
        } finally {
            try { producer?.close() } catch (e: Exception) { /* System.err.println("[$functionName] Warn: Error closing producer: ${e.message}") */ }
            try { consumer?.close() } catch (e: Exception) { /* System.err.println("[$functionName] Warn: Error closing consumer: ${e.message}") */ }
            try {
                functionRunner.stop()
                // You might want to also explicitly delete the function using admin API
                // to ensure a clean state if names weren't unique, but LocalRunner.stop()
                // and unique names per test should be okay.
                // try { admin.functions().deleteFunction("public", "default", functionName) } catch (e: PulsarAdminException.NotFoundException) {}
            } catch (e: Exception) {
                /* System.err.println("[$functionName] Warn: Error stopping function runner: ${e.message}") */
            }
        }
    }

    // --- Test methods (UserProfileTranslator, OrderRecordTranslator, etc.) ---
    // No changes needed in the @Test methods themselves
    @Test fun testUserProfileTranslator() {
        val uid = 789
        val name = "Test User"
        val created = 1_620_000_000L
        val sample = """{"uid":$uid,"name":"$name","created":$created}"""
        val suffix = "userprofile-" + UUID.randomUUID().toString().take(8)

        runTestForFunction(
            UserProfileTranslator::class.java, // Existing test, uses old base names
            inputTopicBaseOld + suffix,
            outputTopicBaseOld + suffix,
            functionNameBaseOld + suffix,
            sample,
            "user-service",
            "USER_PROFILE_EVENT",
            { epochSecondsToISO(it.get("created").asLong()) },
            { orig, data ->
                assertEquals(orig.get("uid").asInt(), data.get("uid").asInt())
                assertEquals(orig.get("name").asText(), data.get("name").asText())
            },
        )
    }

    @Test fun testOrderRecordTranslator() {
        val orderId = "ORD-INT-001"
        val placedAt = "2024-01-15T11:30:00Z"
        val sample = """{"orderId":"$orderId","items":["itemA"],"placedAt":"$placedAt"}"""
        val suffix = "orderrecord-" + UUID.randomUUID().toString().take(8)

        runTestForFunction(
            OrderRecordTranslator::class.java, // Existing test
            inputTopicBaseOld + suffix,
            outputTopicBaseOld + suffix,
            functionNameBaseOld + suffix,
            sample,
            "order-service",
            "ORDER_EVENT",
            { it.get("placedAt").asText() },
            { orig, data ->
                assertEquals(orig.get("orderId").asText(), data.get("orderId").asText())
                assertEquals(orig.get("items"), data.get("items"))
            },
        )
    }

    @Test fun testInventoryUpdateTranslator() {
        val sku = "SKU-INT-123"
        val qty = 150
        val updateTime = 1_620_050_000L
        val sample = """{"sku":"$sku","qty":$qty,"updateTime":$updateTime}"""
        val suffix = "inventoryupdate-" + UUID.randomUUID().toString().take(8)

        runTestForFunction(
            InventoryUpdateTranslator::class.java, // Existing test
            inputTopicBaseOld + suffix,
            outputTopicBaseOld + suffix,
            functionNameBaseOld + suffix,
            sample,
            "inventory-service",
            "INVENTORY_EVENT",
            { epochSecondsToISO(it.get("updateTime").asLong()) },
            { orig, data ->
                assertEquals(orig.get("sku").asText(), data.get("sku").asText())
                assertEquals(orig.get("qty").asInt(), data.get("qty").asInt())
            },
        )
    }

    @Test fun testPaymentNoticeTranslator() {
        val txnId = "TXN-INT-002"
        val time = "2024-01-15T12:00:00Z"
        val sample = """{"txnId":"$txnId","amount":19.99,"currency":"EUR","time":"$time"}"""
        val suffix = "paymentnotice-" + UUID.randomUUID().toString().take(8)

        runTestForFunction(
            PaymentNoticeTranslator::class.java, // Existing test
            inputTopicBaseOld + suffix,
            outputTopicBaseOld + suffix,
            functionNameBaseOld + suffix,
            sample,
            "payment-gateway",
            "PAYMENT_EVENT",
            { it.get("time").asText() },
            { orig, data ->
                assertEquals(orig.get("txnId").asText(), data.get("txnId").asText())
                assertEquals(orig.get("amount").asDouble(), data.get("amount").asDouble())
            },
        )
    }

    @Test fun testShipmentStatusTranslator() {
        val shipId = "SHIP-INT-321"
        val status = "SHIPPED"
        val deliveredAt = 1_620_100_000L
        val sample = """{"shipId":"$shipId","status":"$status","deliveredAt":$deliveredAt}"""
        val suffix = "shipmentstatus-" + UUID.randomUUID().toString().take(8)

        runTestForFunction(
            ShipmentStatusTranslator::class.java, // Existing test
            inputTopicBaseOld + suffix,
            outputTopicBaseOld + suffix,
            functionNameBaseOld + suffix,
            sample,
            "shipping-service",
            "SHIPMENT_EVENT",
            { epochSecondsToISO(it.get("deliveredAt").asLong()) },
            { orig, data ->
                assertEquals(orig.get("shipId").asText(), data.get("shipId").asText())
                assertEquals(orig.get("status").asText(), data.get("status").asText())
            },
        )
    }

    // CMF Tests
    private val CMF_OUTPUT_TOPIC = "persistent://acme/ingest/vehicle-telemetry-common-format" // Centralized CMF output topic
    private val RAW_KINESIS_EVENTS_TOPIC_BASE = "persistent://public/default/raw-kinesis-events-"
    private val RAW_KAFKA_EVENTS_TOPIC_BASE = "persistent://public/default/raw-kafka-events-" // For CalAmp
    private val RAW_HTTP_EVENTS_TOPIC_BASE = "persistent://public/default/raw-http-events-" // For Ford


    // Helper to deserialize CMF message for typed assertions
    // Assuming CommonMessageFormat is available and jackson can handle it
    // You might need to register specific modules if not working out of the box
    // For now, we'll work with JsonNode for data, and specific assertions for sourceSpecificData if needed
    // inline fun <reified T> deserializeCmfData(jsonNode: JsonNode?): T? {
    //     return jsonNode?.let { objectMapper.treeToValue(it, T::class.java) }
    // }

    @Test
    fun testGeotabTranslator() {
        val deviceId = "testDevice123"
        val recordDateTime = "2023-10-26T10:00:00Z"
        val latitude = 34.0522
        val longitude = -118.2437
        val odometerMi = 12345.6
        val ignition = true
        val fuelLevelPercent = 75.5
        val customData = "geotab_specific_value"

        val sampleGeotabInput = """
        {
            "Device_ID": "$deviceId",
            "Record_DateTime": "$recordDateTime",
            "Latitude": $latitude,
            "Longitude": $longitude,
            "Odometer_mi": $odometerMi,
            "Ignition": $ignition,
            "FuelLevel_percent": $fuelLevelPercent,
            "customData": "$customData"
        }
        """.trimIndent()

        val suffix = "geotab-" + UUID.randomUUID().toString().take(8)
        // The function itself is configured to listen to "raw-kinesis-events".
        // For testing, we send to a unique instance of a topic that mimics this,
        // e.g., raw-kinesis-events-somerandomsuffix, to isolate test runs.
        // The FunctionConfig's 'inputs' will use this specific topic for the test.
        val testSpecificInputTopic = RAW_KINESIS_EVENTS_TOPIC_BASE + suffix

        // Ensure the specific input topic for the test is created (handled by runTestForFunction)

        runTestForFunction(
            GeotabTranslator::class.java,
            testSpecificInputTopic, // Test-specific input topic
            CMF_OUTPUT_TOPIC,       // Fixed CMF output topic
            cmfFunctionNameBase + suffix,
            sampleGeotabInput,
            "GEOTAB",
            "VEHICLE_TELEMETRY_EVENT", // Assuming a generic event type for CMF
            { it.get("Record_DateTime").asText() }, // Timestamp extractor from original input
            { originalJson, commonEventData -> // commonEventData is the 'data' field of CommonEvent (the CMF payload)
                // Assertions based on CommonMessageFormat structure
                assertEquals("GEOTAB", commonEventData.get("sourceType").asText())
                assertEquals(deviceId, commonEventData.get("vehicleId").asText())
                // The 'timestamp' field in CommonEvent (outer envelope) is already asserted by runTestForFunction
                // to match the extracted input timestamp.
                // Here we assert 'eventTimestamp' within the CMF payload.
                assertEquals(recordDateTime, commonEventData.get("eventTimestamp").asText())


                val telemetry = commonEventData.get("telemetry")
                assertNotNull(telemetry, "Telemetry data should not be null")
                assertEquals(latitude, telemetry.get("latitude").asDouble(), 0.0001)
                assertEquals(longitude, telemetry.get("longitude").asDouble(), 0.0001)
                assertEquals(odometerMi, telemetry.get("odometer").asDouble(), 0.001)
                assertEquals("ON", telemetry.get("ignitionStatus").asText()) // Geotab: true -> ON
                assertEquals(fuelLevelPercent, telemetry.get("fuelLevelPct").asDouble(), 0.01)

                val sourceSpecificData = commonEventData.get("sourceSpecificData")
                assertNotNull(sourceSpecificData, "SourceSpecificData should not be null")
                // Assuming GeotabTranslator's output for sourceSpecificData matches GeotabInputMessage.SourceSpecificData structure
                assertEquals(customData, sourceSpecificData.get("customData").asText())
            }
        )
    }

    @Test
    fun testCalAmpTranslator() {
        val unitId = "calAmpDevice456"
        val msgTs = 1698314400L // Epoch seconds for 2023-10-26T10:00:00Z
        val latitude = 35.6895
        val longitude = 139.6917
        val odomMi = 54321.0
        val ignStatus = 1 // maps to ON
        val fuelPercent = 60.0
        val calampExtraInfo = "some_calamp_info"

        val sampleCalAmpInput = """
        {
            "unit_id": "$unitId",
            "msg_ts": $msgTs,
            "gps_lat": $latitude,
            "gps_lon": $longitude,
            "odom_mi": $odomMi,
            "ign_status": $ignStatus,
            "fuel_percent": $fuelPercent,
            "calamp_extra": {
                "info": "$calampExtraInfo"
            }
        }
        """.trimIndent()

        val suffix = "calamp-" + UUID.randomUUID().toString().take(8)
        val testSpecificInputTopic = RAW_KAFKA_EVENTS_TOPIC_BASE + suffix

        runTestForFunction(
            CalAmpTranslator::class.java,
            testSpecificInputTopic,
            CMF_OUTPUT_TOPIC,
            cmfFunctionNameBase + suffix,
            sampleCalAmpInput,
            "CALAMP",
            "VEHICLE_TELEMETRY_EVENT",
            { epochSecondsToISO(it.get("msg_ts").asLong()) }, // CalAmp timestamp is epoch seconds
            { originalJson, commonEventData ->
                assertEquals("CALAMP", commonEventData.get("sourceType").asText())
                assertEquals(unitId, commonEventData.get("vehicleId").asText())
                assertEquals(epochSecondsToISO(msgTs), commonEventData.get("eventTimestamp").asText())

                val telemetry = commonEventData.get("telemetry")
                assertNotNull(telemetry)
                assertEquals(latitude, telemetry.get("latitude").asDouble(), 0.0001)
                assertEquals(longitude, telemetry.get("longitude").asDouble(), 0.0001)
                assertEquals(odomMi, telemetry.get("odometer").asDouble(), 0.001)
                assertEquals("ON", telemetry.get("ignitionStatus").asText()) // CalAmp: 1 -> ON
                assertEquals(fuelPercent, telemetry.get("fuelLevelPct").asDouble(), 0.01)

                val sourceSpecificData = commonEventData.get("sourceSpecificData")
                assertNotNull(sourceSpecificData)
                assertEquals(calampExtraInfo, sourceSpecificData.get("calamp_extra").get("info").asText())
            }
        )
    }

    @Test
    fun testFordTranslator() {
        val vin = "FORDVIN789"
        val esn = "FORDSN001"
        val captureTime = 1698318000000L // Epoch milliseconds for 2023-10-26T11:00:00Z
        val latitude = 40.7128
        val longitude = -74.0060
        val fuelLevelPct = 80.0 // Ford uses double for fuel_level_pct
        val fordSpecificField = "value123"

        val sampleFordInput = """
        {
            "vin": "$vin",
            "esn": "$esn",
            "captureTime": $captureTime,
            "coordinates": {
                "lat": $latitude,
                "lon": $longitude
            },
            "fuel_level_pct": $fuelLevelPct,
            "ford_specific_field": "$fordSpecificField"
        }
        """.trimIndent()

        val suffix = "ford-" + UUID.randomUUID().toString().take(8)
        val testSpecificInputTopic = RAW_HTTP_EVENTS_TOPIC_BASE + suffix

        runTestForFunction(
            FordTranslator::class.java,
            testSpecificInputTopic,
            CMF_OUTPUT_TOPIC,
            cmfFunctionNameBase + suffix,
            sampleFordInput,
            "FORD",
            "VEHICLE_TELEMETRY_EVENT",
            // Ford timestamp is epoch milliseconds, convert to ISO string
            { java.time.Instant.ofEpochMilli(it.get("captureTime").asLong()).atOffset(ZoneOffset.UTC).format(ISO_FORMATTER) },
            { originalJson, commonEventData ->
                assertEquals("FORD", commonEventData.get("sourceType").asText())
                assertEquals(vin, commonEventData.get("vehicleId").asText())
                val expectedTimestamp = java.time.Instant.ofEpochMilli(captureTime).atOffset(ZoneOffset.UTC).format(ISO_FORMATTER)
                assertEquals(expectedTimestamp, commonEventData.get("eventTimestamp").asText())

                val telemetry = commonEventData.get("telemetry")
                assertNotNull(telemetry)
                assertEquals(latitude, telemetry.get("latitude").asDouble(), 0.0001)
                assertEquals(longitude, telemetry.get("longitude").asDouble(), 0.0001)
                // Ford does not provide odometer directly in this sample, so we don't assert it.
                // Ford does not provide ignition status directly, so we don't assert it.
                assertEquals(fuelLevelPct, telemetry.get("fuelLevelPct").asDouble(), 0.01)


                val sourceSpecificData = commonEventData.get("sourceSpecificData")
                assertNotNull(sourceSpecificData)
                assertEquals(esn, sourceSpecificData.get("esn").asText())
                assertEquals(fordSpecificField, sourceSpecificData.get("ford_specific_field").asText())
                // Also check that original "coordinates" and "fuel_level_pct" are there if translator keeps them
                assertEquals(latitude, sourceSpecificData.get("coordinates").get("lat").asDouble(), 0.0001)
                assertEquals(longitude, sourceSpecificData.get("coordinates").get("lon").asDouble(), 0.0001)
                assertEquals(fuelLevelPct, sourceSpecificData.get("fuel_level_pct").asDouble(),0.01)


            }
        )
    }
}
