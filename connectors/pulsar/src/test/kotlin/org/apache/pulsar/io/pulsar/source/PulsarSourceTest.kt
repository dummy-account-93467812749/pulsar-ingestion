package org.apache.pulsar.io.pulsar.source

import io.mockk.answers
import io.mockk.atLeast
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import org.apache.pulsar.client.api.ClientBuilder
import org.apache.pulsar.client.api.Consumer
import org.apache.pulsar.client.api.ConsumerBuilder
import org.apache.pulsar.client.api.Message
import org.apache.pulsar.client.api.MessageId
import org.apache.pulsar.client.api.MessageListener
import org.apache.pulsar.client.api.PulsarClient
import org.apache.pulsar.client.api.Schema
import org.apache.pulsar.client.api.SubscriptionInitialPosition
import org.apache.pulsar.client.api.SubscriptionType
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class PulsarSourceTest {

    private lateinit var mockClientBuilder: ClientBuilder
    private lateinit var mockClient: PulsarClient
    private lateinit var mockConsumerBuilder: ConsumerBuilder<ByteArray>
    private lateinit var mockConsumer: Consumer<ByteArray>
    private lateinit var sourceContext: SourceContext
    private lateinit var source: PulsarSource // Instance to be tested

    private val recordQueue = LinkedBlockingQueue<Record<ByteArray>>()

    @BeforeMethod
    fun setUp() {
        mockClientBuilder = mockk(relaxed = true)
        mockClient = mockk(relaxed = true)
        mockConsumerBuilder = mockk(relaxed = true)
        mockConsumer = mockk(relaxed = true)
        sourceContext = mockk(relaxed = true)

        val logger = mockk<Logger>(relaxed = true)
        every { sourceContext.logger } returns logger
        every { sourceContext.instanceId } returns 0

        // Default mock behaviors
        every { mockClientBuilder.build() } returns mockClient
        every { mockClient.newConsumer(any<Schema<ByteArray>>()) } returns mockConsumerBuilder
        every { mockConsumerBuilder.subscribe() } returns mockConsumer

        // For PulsarSourceRecord to work with mocked consumer in 'ack'/'fail'
        every { sourceContext.getPulsarConsumer<ByteArray>() } returns mockConsumer
        every { mockConsumer.acknowledgeAsync(any<MessageId>()) } returns CompletableFuture.completedFuture(null)
        every { mockConsumer.negativeAcknowledge(any<MessageId>()) } returns Unit

        // Instantiate PulsarSource using the internal constructor for testing
        source = PulsarSource(mockClient, mockConsumer)

        // Mock the PushSource.consume method behavior for this instance of PulsarSource
        // This requires PulsarSource to be an open class or to use a mocking framework that supports final classes/methods.
        // For now, assuming we can spy or use a testable version.
        // As `consume` is protected, direct mocking is hard. We test by checking `recordQueue`.
        // The internal `PulsarSource.consumeMessage` will call `this.consume`, which we can't easily mock on a real instance.
        // The `PulsarSourceRecord.ack/fail` rely on `sourceContext.getPulsarConsumer()`, which is mocked.
    }

    @AfterMethod
    fun tearDown() {
        source.close() // This should close the mocked consumer and client
        recordQueue.clear()
    }

    private fun createValidConfigMap(): Map<String, Any> {
        return mapOf(
            "serviceUrl" to "pulsar://localhost:6650",
            "topicNames" to listOf("persistent://public/default/test-input"),
            "subscriptionName" to "test-sub",
            "subscriptionType" to "Shared",
            "subscriptionInitialPosition" to "Earliest",
        )
    }

    @Test
    fun testOpenSubscribesAndStartsProcessingLoop() {
        val configMap = createValidConfigMap()

        // Capture arguments to verify correct setup
        val serviceUrlSlot = slot<String>()
        val topicSlot = slot<List<String>>()
        val subNameSlot = slot<String>()
        val subTypeSlot = slot<SubscriptionType>()
        val subInitialPosSlot = slot<SubscriptionInitialPosition>()
        val messageListenerSlot = slot<MessageListener<ByteArray>>()

        // Mock PulsarClient.builder() static method if possible, or ensure it's not called
        // by injecting mockClient directly via constructor. (Done in setUp)

        every { mockClientBuilder.serviceUrl(capture(serviceUrlSlot)) } returns mockClientBuilder
        // ... (capture other client builder settings if needed)
        every { mockClient.newConsumer(Schema.BYTES) } returns mockConsumerBuilder
        every { mockConsumerBuilder.topics(capture(topicSlot)) } returns mockConsumerBuilder
        every { mockConsumerBuilder.subscriptionName(capture(subNameSlot)) } returns mockConsumerBuilder
        every { mockConsumerBuilder.subscriptionType(capture(subTypeSlot)) } returns mockConsumerBuilder
        every { mockConsumerBuilder.subscriptionInitialPosition(capture(subInitialPosSlot)) } returns mockConsumerBuilder
        every { mockConsumerBuilder.messageListener(capture(messageListenerSlot)) } returns mockConsumerBuilder
        every { mockConsumerBuilder.subscribe() } returns mockConsumer

        source.open(configMap, sourceContext)

        assertEquals(serviceUrlSlot.captured, "pulsar://localhost:6650")
        assertEquals(topicSlot.captured, listOf("persistent://public/default/test-input"))
        assertEquals(subNameSlot.captured, "test-sub")
        assertEquals(subTypeSlot.captured, SubscriptionType.Shared)
        assertEquals(subInitialPosSlot.captured, SubscriptionInitialPosition.Earliest)
        assertTrue(messageListenerSlot.isCaptured, "MessageListener should be set")

        // Let the processing loop run for a bit (it uses an internal queue)
        Thread.sleep(100) // Give some time for the loop to start and potentially poll

        source.close() // Ensure threads are stopped

        verify(atLeast = 1) { mockConsumer.close() } // Check if consumer was closed
        verify { mockClient.close() } // Check if client was closed
    }

    @Test
    fun testMessageProcessingAndAck() {
        val configMap = createValidConfigMap()
        val messageListenerSlot = slot<MessageListener<ByteArray>>()

        every { mockClient.newConsumer(Schema.BYTES) } returns mockConsumerBuilder
        every { mockConsumerBuilder.subscribe() } returns mockConsumer
        every { mockConsumerBuilder.messageListener(capture(messageListenerSlot)) } returns mockConsumerBuilder

        // Spy on the source instance to verify calls to its own consume method
        val sourceSpy = spyk(source, recordPrivateCalls = true)
        val consumedRecordSlot = slot<Record<ByteArray>>()
        every { sourceSpy.consume(capture(consumedRecordSlot)) } answers {
            // Simulate Pulsar IO framework behavior by offering to a test queue
            recordQueue.offer(arg(0))
            CompletableFuture.completedFuture(null)
        }

        sourceSpy.open(configMap, sourceContext)

        // Simulate a message being received by the listener
        val testPayload = "hello pulsar".toByteArray(StandardCharsets.UTF_8)
        val mockMessage = mockk<Message<ByteArray>>(relaxed = true)
        every { mockMessage.data } returns testPayload
        every { mockMessage.messageId } returns mockk<MessageId>() // Mock MessageId
        every { mockMessage.key } returns "testKey"
        every { mockMessage.eventTime } returns System.currentTimeMillis()
        every { mockMessage.sequenceId } returns 1L
        every { mockMessage.topicName } returns "persistent://public/default/test-input"
        every { mockMessage.properties } returns mapOf("prop1" to "value1")

        // Manually trigger the listener with the mock message
        assertTrue(messageListenerSlot.isCaptured)
        messageListenerSlot.captured.received(mockConsumer, mockMessage)

        // Wait for the processing loop to pick up the message from incomingMessages
        val processedRecord = recordQueue.poll(5, TimeUnit.SECONDS)

        assertNotNull(processedRecord, "Record should have been processed and put into queue")
        assertEquals(String(processedRecord.value), "hello pulsar")
        assertEquals(processedRecord.key.orElse(null), "testKey")
        assertTrue(processedRecord.properties.containsKey("prop1"))

        // Verify that acknowledgeAsync was called on the original consumer
        verify(timeout = 1000) { mockConsumer.acknowledgeAsync(mockMessage.messageId) }

        sourceSpy.close()
    }

    @Test
    fun testNegativeAckOnProcessingError() {
        val configMap = createValidConfigMap()
        val messageListenerSlot = slot<MessageListener<ByteArray>>()

        every { mockClient.newConsumer(Schema.BYTES) } returns mockConsumerBuilder
        every { mockConsumerBuilder.subscribe() } returns mockConsumer
        every { mockConsumerBuilder.messageListener(capture(messageListenerSlot)) } returns mockConsumerBuilder

        val sourceSpy = spyk(source, recordPrivateCalls = true)
        // Simulate an error during processing by PushSource.consume()
        every { sourceSpy.consume(any()) } throws RuntimeException("Test processing error")

        sourceSpy.open(configMap, sourceContext)

        val testPayload = "error message".toByteArray(StandardCharsets.UTF_8)
        val mockMessage = mockk<Message<ByteArray>>(relaxed = true)
        every { mockMessage.data } returns testPayload
        every { mockMessage.messageId } returns mockk<MessageId>()

        assertTrue(messageListenerSlot.isCaptured)
        messageListenerSlot.captured.received(mockConsumer, mockMessage) // Manually trigger listener

        // Verify that negativeAcknowledge was called on the original consumer
        verify(timeout = 1000) { mockConsumer.negativeAcknowledge(mockMessage.messageId) }

        sourceSpy.close()
    }
}
