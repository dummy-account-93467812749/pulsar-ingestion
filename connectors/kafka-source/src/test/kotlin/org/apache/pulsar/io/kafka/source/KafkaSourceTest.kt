package org.apache.pulsar.io.kafka.source

import io.mockk.arg
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.pulsar.functions.api.Record
import org.apache.pulsar.io.core.SourceContext
import org.slf4j.Logger
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNotNull
import org.testng.Assert.assertTrue
import org.testng.annotations.AfterMethod
import org.testng.annotations.BeforeMethod
import org.testng.annotations.Test
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class KafkaSourceTest {

    @Suppress("UNCHECKED_CAST")
    private val mockKafkaConsumer = mockk<KafkaConsumer<Any, Any>>(relaxed = true)
    private val recordQueue = LinkedBlockingQueue<Record<ByteArray>>()
    private lateinit var kafkaSource: KafkaSource // Will be spy
    private lateinit var sourceContext: SourceContext
    private lateinit var config: KafkaSourceConfig

    @BeforeMethod
    fun setUp() {
        sourceContext = mockk(relaxed = true)
        val logger = mockk<Logger>(relaxed = true)
        every { sourceContext.logger } returns logger
        every { sourceContext.instanceId } returns 0

        config = KafkaSourceConfig(
            bootstrapServers = "dummy:9092",
            topic = "test-topic",
            groupId = "test-group",
            pollIntervalMs = 10,
        )

        // Inject mock consumer via internal constructor
        kafkaSource = spyk(KafkaSource(mockKafkaConsumer), recordPrivateCalls = true)

        // Mock consume behavior
        every { kafkaSource.consume(any()) } answers {
            val record = arg<Record<ByteArray>>(0)
            recordQueue.offer(record)
            CompletableFuture.completedFuture(null)
        }
    }

    @AfterMethod
    fun tearDown() {
        kafkaSource.close()
        recordQueue.clear()
    }

    @Test
    fun testOpenSubscribesAndStartsPolling() {
        val subscribeTopicSlot = slot<List<String>>()
        every { mockKafkaConsumer.subscribe(capture(subscribeTopicSlot)) } returns Unit

        kafkaSource.open(config.toMap(), sourceContext)

        assertTrue(subscribeTopicSlot.isCaptured)
        assertEquals(subscribeTopicSlot.captured, listOf("test-topic"))

        Thread.sleep(50)
        kafkaSource.close()

        verify(atLeast = 1) { mockKafkaConsumer.poll(any<Duration>()) }
    }

    @Test
    fun testRecordProcessing() {
        val key = "testKey"
        val value = "testValue"
        val topicPartition = TopicPartition(config.topic, 0)
        val consumerRecord = ConsumerRecord<Any, Any>(
            config.topic, 0, 0L, System.currentTimeMillis(),
            org.apache.kafka.common.record.TimestampType.CREATE_TIME,
            0L, 0, 0, key, value.toByteArray(StandardCharsets.UTF_8),
        )
        val recordsMap = mapOf(topicPartition to listOf(consumerRecord))
        val consumerRecords = ConsumerRecords<Any, Any>(recordsMap)

        every { mockKafkaConsumer.poll(any<Duration>()) } returns consumerRecords andThenThrows InterruptedException("Stop polling")

        kafkaSource.open(config.toMap(), sourceContext)

        val consumedPulsarRecord = recordQueue.poll(5, TimeUnit.SECONDS)
        assertNotNull(consumedPulsarRecord, "Record should have been consumed")
        assertEquals(String(consumedPulsarRecord.value, StandardCharsets.UTF_8), value)
        assertTrue(consumedPulsarRecord.key.isPresent)
        assertEquals(consumedPulsarRecord.key.get(), key)
        assertEquals(consumedPulsarRecord.properties["kafka_topic"], config.topic)
        assertEquals(consumedPulsarRecord.properties["kafka_partition"], "0")
        assertEquals(consumedPulsarRecord.properties["kafka_offset"], "0")

        kafkaSource.close()
    }

    @Test
    fun testCloseStopsPollingAndCleansUp() {
        every { mockKafkaConsumer.poll(any<Duration>()) } answers {
            Thread.sleep(20)
            ConsumerRecords.empty()
        }

        kafkaSource.open(config.toMap(), sourceContext)
        Thread.sleep(50)

        kafkaSource.close()

        verify { mockKafkaConsumer.wakeup() }
        verify(timeout = 2000) { mockKafkaConsumer.close(any<Duration>()) }
    }
}

// Extension function must be declared at top level
fun KafkaSourceConfig.toMap(): Map<String, Any> {
    return mapOf(
        "bootstrapServers" to bootstrapServers,
        "topic" to topic,
        "groupId" to groupId,
        "keyDeserializer" to keyDeserializer,
        "valueDeserializer" to valueDeserializer,
        "autoOffsetReset" to autoOffsetReset,
        "maxPollRecords" to maxPollRecords,
        "pollIntervalMs" to pollIntervalMs,
    ) + listOfNotNull(
        securityProtocol?.let { "securityProtocol" to it },
        saslMechanism?.let { "saslMechanism" to it },
        saslJaasConfig?.let { "saslJaasConfig" to it },
        sslTruststoreLocation?.let { "sslTruststoreLocation" to it },
        sslTruststorePassword?.let { "sslTruststorePassword" to it },
        sslKeystoreLocation?.let { "sslKeystoreLocation" to it },
        sslKeystorePassword?.let { "sslKeystorePassword" to it },
        sslKeyPassword?.let { "sslKeyPassword" to it },
    ).toMap() + (additionalProperties?.entries?.associate { "additionalProperties.\${it.key}" to it.value } ?: emptyMap())
}
