package org.apache.pulsar.io.pulsar.source

import org.apache.pulsar.client.api.AuthenticationFactory
import org.apache.pulsar.client.api.ClientBuilder
import org.apache.pulsar.client.api.Consumer
import org.apache.pulsar.client.api.ConsumerBuilder
import org.apache.pulsar.client.api.Message
import org.apache.pulsar.client.api.PulsarClient
import org.apache.pulsar.client.api.Schema
import org.apache.pulsar.functions.api.Record
import org.apache.pulsar.io.core.PushSource
import org.apache.pulsar.io.core.SourceContext
import org.slf4j.LoggerFactory
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

class PulsarSource : PushSource<ByteArray>() {

    private lateinit var config: PulsarSourceConfig
    private lateinit var client: PulsarClient
    private lateinit var consumer: Consumer<ByteArray>
    private lateinit var consumerExecutor: ExecutorService
    private val running = AtomicBoolean(false)
    private val incomingMessages = LinkedBlockingQueue<Message<ByteArray>>(1000) // Default internal queue

    companion object {
        private val LOG = LoggerFactory.getLogger(PulsarSource::class.java)
    }

    // Internal constructor for testing to allow injecting a mocked PulsarClient and Consumer
    internal constructor(testClient: PulsarClient, testConsumer: Consumer<ByteArray>) : this() {
        this.client = testClient
        this.consumer = testConsumer
    }

    // Default constructor for normal operation
    constructor() : super()

    override fun open(configMap: Map<String, Any>, sourceContext: SourceContext) {
        LOG.info("Opening PulsarSource with config: {}", configMap)
        config = PulsarSourceConfig.load(configMap)
        config.validate()

        if (!::client.isInitialized) { // Initialize only if not already injected for testing
            val clientBuilder: ClientBuilder = PulsarClient.builder()
                .serviceUrl(config.serviceUrl)
                .ioThreads(5) // Example: Default or configurable
                .listenerThreads(2) // Example: Default or configurable

            if (!config.authPluginClassName.isNullOrBlank()) {
                clientBuilder.authentication(AuthenticationFactory.create(config.authPluginClassName, config.authParams ?: ""))
            }

            config.tlsTrustCertsFilePath?.let { clientBuilder.tlsTrustCertsFilePath(it) }
            clientBuilder.allowTlsInsecureConnection(config.tlsAllowInsecureConnection)
            clientBuilder.enableTlsHostnameVerification(config.tlsHostnameVerificationEnable)
            config.tlsKeyFilePath?.let { keyPath ->
                config.tlsCertificateFilePath?.let { certPath ->
                    clientBuilder.tlsKeyFilePath(keyPath)
                    clientBuilder.tlsCertificateFilePath(certPath)
                }
            }
            // Apply other client settings from config.clientProperties if needed

            try {
                client = clientBuilder.build()
                LOG.info("PulsarClient created successfully for service URL: {}", config.serviceUrl)
            } catch (e: Exception) {
                LOG.error("Failed to create PulsarClient", e)
                throw RuntimeException("Failed to create PulsarClient", e)
            }
        }

        if (!::consumer.isInitialized) { // Initialize only if not already injected for testing
            val consumerBuilder: ConsumerBuilder<ByteArray> = client.newConsumer(Schema.BYTES)
                .subscriptionName(config.subscriptionName)
                .subscriptionType(config.subscriptionType)
                .subscriptionInitialPosition(config.subscriptionInitialPosition)
                .receiverQueueSize(config.receiverQueueSize)
                .ackTimeout(config.ackTimeoutMillis, TimeUnit.MILLISECONDS)
                .negativeAckRedeliveryDelay(config.negativeAckRedeliveryDelayMicros, TimeUnit.MICROSECONDS)

            if (!config.topicNames.isNullOrEmpty()) {
                consumerBuilder.topics(config.topicNames)
            } else if (!config.topicsPattern.isNullOrBlank()) {
                consumerBuilder.topicsPattern(Pattern.compile(config.topicsPattern))
            }

            config.deadLetterPolicy?.let { dlp ->
                val deadLetterPolicyBuilder = org.apache.pulsar.client.api.DeadLetterPolicy.builder()
                    .maxRedeliverCount(dlp.maxRedeliverCount)
                dlp.deadLetterTopic?.let { deadLetterPolicyBuilder.deadLetterTopic(it) }
                dlp.initialSubscriptionName?.let { deadLetterPolicyBuilder.initialSubscriptionName(it) }
                consumerBuilder.deadLetterPolicy(deadLetterPolicyBuilder.build())
            }
            // Apply config.consumerProperties directly to consumerBuilder
            config.consumerProperties?.forEach { (key, value) ->
                consumerBuilder.property(key, value.toString()) // Assuming properties are string-based for simplicity
            }
            consumerBuilder.messageListener { cons, msg ->
                try {
                    if (running.get()) {
                        val offered = incomingMessages.offer(msg, 1, TimeUnit.SECONDS) // Timeout to prevent blocking indefinitely
                        if (!offered) {
                            LOG.warn(
                                "Internal message queue is full for subscription {}. Message from {} will be redelivered later.",
                                config.subscriptionName,
                                msg.messageId,
                            )
                            cons.negativeAcknowledge(msg) // Nack if queue is full to trigger redelivery
                        }
                        // Acknowledgment will happen after processing in the processing loop
                    } else {
                        // If not running, do not process or ack/nack. Let consumer.close() handle pending messages.
                        LOG.info("Source not running, ignoring message from listener: {}", msg.messageId)
                    }
                } catch (e: InterruptedException) {
                    LOG.warn("Message listener interrupted while offering message to queue for subscription {}.", config.subscriptionName)
                    Thread.currentThread().interrupt() // Restore interrupt status
                } catch (e: Exception) {
                    LOG.error("Error in message listener for subscription {}: {}", config.subscriptionName, e.message, e)
                    cons.negativeAcknowledge(msg) // Nack on unexpected error
                }
            }

            try {
                consumer = consumerBuilder.subscribe()
                LOG.info("Pulsar Consumer subscribed successfully to topics/pattern for subscription: {}", config.subscriptionName)
            } catch (e: Exception) {
                LOG.error("Failed to subscribe Pulsar Consumer", e)
                throw RuntimeException("Failed to subscribe Pulsar Consumer", e)
            }
        }

        running.set(true)
        consumerExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "pulsar-source-processing-${config.subscriptionName}-${sourceContext.instanceId}")
        }

        consumerExecutor.submit {
            LOG.info("PulsarSource processing thread started for subscription {}", config.subscriptionName)
            while (running.get()) {
                try {
                    val message = incomingMessages.poll(config.ackTimeoutMillis - 1000, TimeUnit.MILLISECONDS) // Poll with timeout
                    if (message != null) {
                        processMessage(message, sourceContext)
                    } else if (running.get()) {
                        // No message received within timeout, continue polling
                        LOG.trace("No message in internal queue for subscription {}, continuing poll.", config.subscriptionName)
                    }
                } catch (e: InterruptedException) {
                    LOG.info("PulsarSource processing thread interrupted for subscription {}.", config.subscriptionName)
                    Thread.currentThread().interrupt() // Restore interrupt status
                    break // Exit loop
                } catch (e: Exception) {
                    LOG.error("Error in PulsarSource processing loop for subscription {}: {}", config.subscriptionName, e.message, e)
                    // Potentially pause or implement backoff for continuous errors
                }
            }
            LOG.info("PulsarSource processing thread finished for subscription {}", config.subscriptionName)
        }
        LOG.info("PulsarSource started successfully for subscription {}", config.subscriptionName)
    }

    private fun processMessage(message: Message<ByteArray>, sourceContext: SourceContext) {
        try {
            val record = PulsarSourceRecord(message, sourceContext)
            consume(record) // PushSource.consume()
            consumer.acknowledgeAsync(message.messageId)
                .exceptionally { throwable ->
                    LOG.error("Failed to acknowledge message {} on subscription {}", message.messageId, config.subscriptionName, throwable)
                    // Optional: implement retry for acknowledgment or specific error handling
                    consumer.negativeAcknowledge(message) // Nack if ack fails
                    null
                }
            LOG.trace("Processed and acknowledged message {} from subscription {}", message.messageId, config.subscriptionName)
        } catch (e: Exception) {
            LOG.error(
                "Error processing message {} from subscription {}. Sending negative acknowledgment.",
                message.messageId,
                config.subscriptionName,
                e,
            )
            consumer.negativeAcknowledge(message)
            // Optionally, implement retry logic within the source or rely on Pulsar's redelivery
        }
    }

    override fun close() {
        LOG.info("Closing PulsarSource for subscription {}", config.subscriptionName)
        running.set(false)

        consumerExecutor?.shutdown()
        try {
            if (consumerExecutor?.awaitTermination(10, TimeUnit.SECONDS) == false) {
                LOG.warn("Consumer executor for subscription {} did not terminate in time, forcing shutdown.", config.subscriptionName)
                consumerExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            LOG.warn("Interrupted while waiting for consumer executor for subscription {} to terminate.", config.subscriptionName)
            consumerExecutor?.shutdownNow()
            Thread.currentThread().interrupt()
        }

        if (::consumer.isInitialized) {
            try {
                consumer.close()
                LOG.info("Pulsar Consumer closed for subscription {}", config.subscriptionName)
            } catch (e: Exception) {
                LOG.error("Failed to close Pulsar Consumer for subscription {}", config.subscriptionName, e)
            }
        }
        if (::client.isInitialized) {
            try {
                client.close()
                LOG.info("PulsarClient closed for service URL {}", config.serviceUrl)
            } catch (e: Exception) {
                LOG.error("Failed to close PulsarClient for service URL {}", config.serviceUrl, e)
            }
        }
        LOG.info("PulsarSource closed for subscription {}", config.subscriptionName)
    }

    class PulsarSourceRecord(
        private val message: Message<ByteArray>,
        private val srcCtx: SourceContext,
    ) : Record<ByteArray> {

        override fun getKey(): Optional<String> = if (message.hasKey()) Optional.of(message.key) else Optional.empty()

        override fun getValue(): ByteArray = message.data

        override fun getEventTime(): Optional<Long> =
            if (message.eventTime > 0) Optional.of(message.eventTime) else Optional.empty()

        // Note: Pulsar's Java client API getSequenceId() returns long, not Long.
        // The Record interface expects Optional<Long>.
        override fun getSequenceId(): Optional<Long> =
            if (message.sequenceId >= 0) Optional.of(message.sequenceId) else Optional.empty()

        override fun getProperties(): Map<String, String> = message.properties ?: emptyMap()

        override fun getDestinationTopic(): Optional<String> = Optional.ofNullable(message.topicName)

        override fun getMessage(): Optional<org.apache.pulsar.client.api.Message<ByteArray>> = Optional.of(message)

        override fun getRecordContext(): Optional<org.apache.pulsar.io.core.RecordContext> {
            return Optional.of(PulsarRecordContext(message, consumer = srcCtx.getPulsarConsumer()))
        }

        override fun ack() {
            srcCtx.getPulsarConsumer<ByteArray>()?.acknowledgeAsync(message.messageId)
                ?.exceptionally { throwable ->
                    LOG.warn("Failed to ack message from PulsarSourceRecord: ${message.messageId}", throwable)
                    null
                }
        }

        override fun fail() {
            LOG.warn("PulsarSourceRecord.fail() called for message: ${message.messageId}. Sending negative acknowledgment.")
            srcCtx.getPulsarConsumer<ByteArray>()?.negativeAcknowledge(message.messageId)
        }
    }

    // Basic RecordContext for Pulsar messages
    class PulsarRecordContext(
        private val msg: Message<ByteArray>,
        private val consumer: Consumer<ByteArray>?,
    ) : org.apache.pulsar.io.core.RecordContext {
        override fun getPartitionId(): Optional<String> = Optional.ofNullable(msg.topicName) // Or specific partition info if available
        override fun getRecordSequence(): Optional<Long> = if (msg.sequenceId >= 0) Optional.of(msg.sequenceId) else Optional.empty()
        override fun ack() {
            consumer?.acknowledgeAsync(msg.messageId)
                ?.exceptionally { throwable ->
                    LOG.warn("Failed to ack message from PulsarRecordContext: ${msg.messageId}", throwable)
                    null
                }
        }
        override fun fail() {
            LOG.warn("PulsarRecordContext.fail() called for message: ${msg.messageId}. Sending negative acknowledgment.")
            consumer?.negativeAcknowledge(msg.messageId)
        }
        override fun getAckFuture(): CompletableFuture<Void> {
            return consumer?.acknowledgeAsync(msg.messageId) ?: CompletableFuture.failedFuture(IllegalStateException("Consumer not available"))
        }
        override fun getNackFuture(): CompletableFuture<Void> {
            val future = CompletableFuture<Void>()
            try {
                consumer?.negativeAcknowledge(msg.messageId)
                future.complete(null)
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
            return future
        }
    }
}

// Helper extension to get the underlying Pulsar Consumer from SourceContext if possible
// This is a bit of a hack and relies on SourceContext implementation details or future interface enhancements.
// For now, this won't work directly as SourceContext doesn't expose the consumer.
// The ack/fail logic in PulsarSourceRecord will use the main consumer instance from PulsarSource.
// This is left here as a placeholder for a more ideal RecordContext implementation.
fun <T> SourceContext.getPulsarConsumer(): Consumer<T>? {
    // This is a placeholder. In a real scenario, if SourceContext provided access:
    // return this.userConfigMap["_pulsar_consumer_"] as? Consumer<T>
    return null
}
