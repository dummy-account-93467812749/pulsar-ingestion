package org.apache.pulsar.io.kinesis

import org.apache.pulsar.client.admin.PulsarAdmin
import org.apache.pulsar.client.api.*
import org.junit.jupiter.api.*
import org.slf4j.LoggerFactory
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.containers.PulsarContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.kinesis.KinesisClient
import software.amazon.awssdk.services.kinesis.model.*
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS) // Allows non-static @BeforeAll and @AfterAll
class KinesisSourceIntegrationTest {

    companion object {
        private val logger = LoggerFactory.getLogger(KinesisSourceIntegrationTest::class.java)
        private const val PULSAR_IMAGE = "apachepulsar/pulsar:2.10.0"
        private val LOCALSTACK_IMAGE = DockerImageName.parse("localstack/localstack:2.2.0") // Use a recent version

        private const val CONNECTOR_TYPE = "kinesis" // from connector.yaml
        private const val CONNECTOR_NAME = "kinesis-source-test"
        private const val PULSAR_OUTPUT_TOPIC = "persistent://public/default/kinesis-topic" // from connector.yaml
        private const val KINESIS_TEST_STREAM = "test-kinesis-stream"
        private const val KINESIS_TEST_PARTITION_KEY = "test-partition-key"
        private val AWS_REGION = Region.US_EAST_1 // LocalStack default region
        private const val DUMMY_ACCESS_KEY = "test-access-key"
        private const val DUMMY_SECRET_KEY = "test-secret-key"
    }

    private lateinit var pulsarContainer: PulsarContainer
    private lateinit var localStackContainer: LocalStackContainer
    private lateinit var adminClient: PulsarAdmin
    private lateinit var pulsarClient: PulsarClient
    private lateinit var kinesisClient: KinesisClient

    private lateinit var tempKinesisConfigFile: File

    @BeforeAll
    fun setup() {
        pulsarContainer = PulsarContainer(DockerImageName.parse(PULSAR_IMAGE)).apply {
            waitingFor(Wait.forHttp("/admin/v2/clusters/standalone").forStatusCode(200).forPort(8080))
        }
        pulsarContainer.start()

        localStackContainer = LocalStackContainer(LOCALSTACK_IMAGE)
            .withServices(LocalStackContainer.Service.KINESIS)
            .withEnv("DEFAULT_REGION", AWS_REGION.id())
        localStackContainer.start()

        logger.info("Pulsar container started. HTTP Service URL: ${pulsarContainer.httpServiceUrl}, Broker URL: ${pulsarContainer.pulsarBrokerUrl}")
        logger.info("LocalStack container started. Kinesis endpoint: ${localStackContainer.getEndpointOverride(LocalStackContainer.Service.KINESIS)}")

        adminClient = PulsarAdmin.builder()
            .serviceHttpUrl(pulsarContainer.httpServiceUrl)
            .build()
        pulsarClient = PulsarClient.builder()
            .serviceUrl(pulsarContainer.pulsarBrokerUrl)
            .build()

        kinesisClient = KinesisClient.builder()
            .endpointOverride(localStackContainer.getEndpointOverride(LocalStackContainer.Service.KINESIS))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(DUMMY_ACCESS_KEY, DUMMY_SECRET_KEY)))
            .region(AWS_REGION)
            .httpClient(UrlConnectionHttpClient.builder().build()) // Recommended for LocalStack
            .build()
        
        createKinesisStream()

        tempKinesisConfigFile = createTemporaryKinesisConfig()
        deployConnector(tempKinesisConfigFile)
    }
    
    private fun createKinesisStream() {
        try {
            kinesisClient.createStream(CreateStreamRequest.builder().streamName(KINESIS_TEST_STREAM).shardCount(1).build())
            logger.info("Kinesis stream '$KINESIS_TEST_STREAM' created.")
            // Wait for stream to become active
            var streamStatus = ""
            for (i in 1..10) {
                val describeStreamResponse = kinesisClient.describeStream(DescribeStreamRequest.builder().streamName(KINESIS_TEST_STREAM).build())
                streamStatus = describeStreamResponse.streamDescription().streamStatusAsString()
                if (streamStatus == StreamStatus.ACTIVE.toString()) {
                    logger.info("Kinesis stream '$KINESIS_TEST_STREAM' is active.")
                    return
                }
                logger.info("Waiting for Kinesis stream '$KINESIS_TEST_STREAM' to become active (current: $streamStatus)...")
                Thread.sleep(2000)
            }
            throw RuntimeException("Kinesis stream $KINESIS_TEST_STREAM did not become active. Last status: $streamStatus")
        } catch (e: ResourceInUseException) {
            logger.warn("Kinesis stream '$KINESIS_TEST_STREAM' already exists. Assuming it's usable.")
        } catch (e: Exception) {
            logger.error("Failed to create or verify Kinesis stream '$KINESIS_TEST_STREAM'", e)
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
        kinesisClient.close()
        adminClient.close()
        pulsarClient.close()
        localStackContainer.stop()
        pulsarContainer.stop()
        if (::tempKinesisConfigFile.isInitialized && tempKinesisConfigFile.exists()) {
            tempKinesisConfigFile.delete()
        }
    }

    private fun createTemporaryKinesisConfig(): File {
        val originalConfigPath = "connectors/kinesis/config.sample.yml"
        val tempFile = Files.createTempFile("test-config-kinesis", ".yml").toFile()

        var content = File(originalConfigPath).readText()
        content = content.replace(Regex("kinesisStreamName:\\s*\".*\""), "kinesisStreamName: \"$KINESIS_TEST_STREAM\"")
        content = content.replace(Regex("awsRegion:\\s*\".*\""), "awsRegion: \"${AWS_REGION.id()}\"")
        content = content.replace(Regex("awsAccessKeyId:\\s*\".*\""), "awsAccessKeyId: \"$DUMMY_ACCESS_KEY\"")
        content = content.replace(Regex("awsSecretAccessKey:\\s*\".*\""), "awsSecretAccessKey: \"$DUMMY_SECRET_KEY\"")
        content = content.replace(Regex("# kinesisEndpoint:\\s*\".*\""), "kinesisEndpoint: \"${localStackContainer.getEndpointOverride(LocalStackContainer.Service.KINESIS)}\"")
        content = content.replace(Regex("# cloudwatchEndpoint:\\s*\".*\""), "cloudwatchEndpoint: \"${localStackContainer.getEndpointOverride(LocalStackContainer.Service.CLOUDWATCH)}\"") // Kinesis connector might use CloudWatch
        content = content.replace(Regex("initialPositionInStream:\\s*\".*\""), "initialPositionInStream: \"TRIM_HORIZON\"")


        tempFile.writeText(content)
        logger.info("Temporary Kinesis config created at: ${tempFile.absolutePath} with content:\n$content")
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
            Thread.sleep(2000) // Kinesis connector can take a bit longer to initialize shards
        }
        if (!running) {
            val status = try { adminClient.sources().getSourceStatus("public", "default", CONNECTOR_NAME) } catch (e: Exception) { null }
            logger.error("Connector $CONNECTOR_NAME failed to start. Status: $status")
            throw RuntimeException("Connector $CONNECTOR_NAME failed to start. Status: ${status?.instances?.firstOrNull()?.status?.error}")
        }
    }

    private fun findConnectorNar(): String {
        val projectRoot = File(System.getProperty("user.dir")).parentFile.parentFile // Heuristic
        val connectorBuildDir = File(projectRoot, "connectors/kinesis/build/libs")
        logger.info("Attempting to find NAR in directory: ${connectorBuildDir.absolutePath}")
        
        connectorBuildDir.listFiles { _, name -> name.endsWith(".nar") && name.startsWith("pulsar-io-kinesis") }?.firstOrNull()?.let {
            logger.info("Found NAR: ${it.absolutePath}")
            return it.absolutePath
        }
        val targetDir = File(System.getProperty("user.dir"), "../../../connectors/kinesis/build/libs/")
        targetDir.listFiles { _, name -> name.endsWith(".nar") && name.startsWith("pulsar-io-kinesis") }?.firstOrNull()?.let {
            logger.info("Found NAR in target: ${it.absolutePath}")
            return it.absolutePath
        }
        throw IllegalStateException("Connector NAR file (pulsar-io-kinesis-*.nar) not found. Searched standard locations.")
    }

    @Test
    fun `should receive messages from Kinesis stream via Kinesis source connector`() {
        val messageCount = 3 // Keep it small for Kinesis tests
        val testMessagePrefix = "kinesis-test-payload-"

        // 1. Send messages to Kinesis (LocalStack)
        for (i in 0 until messageCount) {
            val payload = "$testMessagePrefix$i"
            val putRecordRequest = PutRecordRequest.builder()
                .streamName(KINESIS_TEST_STREAM)
                .partitionKey(KINESIS_TEST_PARTITION_KEY)
                .data(SdkBytes.fromUtf8String(payload))
                .build()
            val result = kinesisClient.putRecord(putRecordRequest)
            logger.info("Sent message to Kinesis: $payload (Shard ID: ${result.shardId()}, Sequence: ${result.sequenceNumber()})")
        }

        // 2. Initialize Pulsar consumer and receive messages
        val receivedMessages = mutableListOf<String>()
        var consumer: Consumer<String>? = null
        try {
            consumer = pulsarClient.newConsumer(Schema.STRING)
                .topic(PULSAR_OUTPUT_TOPIC)
                .subscriptionName("test-kinesis-source-subscription-${System.currentTimeMillis()}")
                .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                .subscribe()

            for (i in 0 until messageCount) {
                val msg: Message<String>? = consumer.receive(45, TimeUnit.SECONDS) // Kinesis can have some latency
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
        assertEquals(messageCount, receivedMessages.size, "Should have received all messages sent to Kinesis.")
        for (i in 0 until messageCount) {
            assertEquals("$testMessagePrefix$i", receivedMessages[i], "Message content mismatch for message $i.")
        }
        logger.info("Successfully received and verified $messageCount messages from Kinesis via Pulsar Kinesis source connector.")
    }
}
