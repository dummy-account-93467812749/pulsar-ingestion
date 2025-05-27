package org.apache.pulsar.io.azureeventhub.source

import com.azure.identity.DefaultAzureCredentialBuilder
import com.azure.messaging.eventhubs.EventHubClientBuilder
import com.azure.messaging.eventhubs.EventProcessorClient
import com.azure.messaging.eventhubs.EventProcessorClientBuilder
import com.azure.messaging.eventhubs.checkpointstore.blob.BlobCheckpointStore
import com.azure.messaging.eventhubs.models.ErrorContext
import com.azure.messaging.eventhubs.models.EventContext
import com.azure.storage.blob.BlobContainerAsyncClient
import com.azure.storage.blob.BlobContainerClientBuilder
import org.apache.pulsar.functions.api.Record
import org.apache.pulsar.io.core.PushSource
import org.apache.pulsar.io.core.SourceContext
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

class AzureEventHubSource : PushSource<ByteArray>() {

    private lateinit var config: AzureEventHubSourceConfig
    private lateinit var eventProcessorClient: EventProcessorClient
    private lateinit var sourceContext: SourceContext

    companion object {
        private val LOG = LoggerFactory.getLogger(AzureEventHubSource::class.java)
    }

    override fun open(configMap: Map<String, Any>, sourceContext: SourceContext) {
        LOG.info("Opening Azure Event Hubs Source with config: {}", configMap)
        this.sourceContext = sourceContext
        config = AzureEventHubSourceConfig.load(configMap)
        config.validate()

        val checkpointStore = setupCheckpointStore()

        val eventProcessorClientBuilder = EventProcessorClientBuilder()
            .consumerGroup(config.consumerGroup)
            .fullyQualifiedNamespace(config.fullyQualifiedNamespace)
            .eventHubName(config.eventHubName)
            .checkpointStore(checkpointStore)
            .processEvent(ProcessEvent(sourceContext, this::consumeEventData))
            .processError(ProcessError(sourceContext))

        // Configure authentication
        if (!config.connectionString.isNullOrBlank()) {
            eventProcessorClientBuilder.connectionString(config.connectionString, config.eventHubName)
            LOG.info("Configured EventProcessorClient with Connection String for Event Hub: ${config.eventHubName}")
        } else {
            val credentialBuilder = DefaultAzureCredentialBuilder()
            config.credential?.authorityHost?.let { credentialBuilder.authorityHost(it) }
            // Note: tenantId, clientId, clientSecret are picked up by DefaultAzureCredential from environment
            // or other configurations if not set explicitly here. For specific scenarios like
            // single-tenant apps or specific service principals, they might be needed.
            // For now, relying on DefaultAzureCredential's chain.
            eventProcessorClientBuilder.credential(credentialBuilder.build())
            LOG.info("Configured EventProcessorClient with DefaultAzureCredential for Event Hub: ${config.eventHubName}")
        }

        // Optional EventProcessorClient settings
        config.prefetchCount?.let { eventProcessorClientBuilder.prefetchCount(it) }
        config.initialPartitionEventPosition?.let { positions ->
            positions.forEach { (partitionId, positionString) ->
                // Simplified position handling; real implementation would need robust parsing
                // and mapping to EventPosition.fromOffset(), .fromSequenceNumber(), .fromEnqueuedTime(), .earliest(), .latest()
                // For now, assuming simple string mapping or ignoring if complex.
                // This part requires a more sophisticated setup if fully implemented.
                LOG.warn("InitialPartitionEventPosition for partition $partitionId not fully implemented for position string '$positionString'. Using SDK default.")
            }
        }
        config.maxBatchSize?.let { eventProcessorClientBuilder.maxBatchSize(it) }
        config.maxWaitTimeInSeconds?.let { eventProcessorClientBuilder.maxWaitTime(java.time.Duration.ofSeconds(it.toLong())) }


        try {
            eventProcessorClient = eventProcessorClientBuilder.buildEventProcessorClient()
            LOG.info("EventProcessorClient built successfully. Starting event processing.")
            eventProcessorClient.start()
            LOG.info("Azure Event Hubs Source started successfully.")
        } catch (e: Exception) {
            LOG.error("Failed to build or start EventProcessorClient", e)
            throw RuntimeException("Failed to initialize Azure Event Hubs Source", e)
        }
    }

    private fun setupCheckpointStore(): BlobCheckpointStore {
        val checkpointConfig = config.checkpointStore
            ?: throw IllegalStateException("CheckpointStoreConfig cannot be null at this point (validation should have caught this)")

        val blobContainerAsyncClient: BlobContainerAsyncClient
        val blobContainerClientBuilder = BlobContainerClientBuilder()

        if (!checkpointConfig.blobContainerUrl.isNullOrBlank()) {
            blobContainerClientBuilder.containerUrl(checkpointConfig.blobContainerUrl)
            // Credential for blob store - use top-level credential if storage specific one isn't provided
            if (config.connectionString.isNullOrBlank()) { // Only use DefaultAzureCredential if no EH connection string
                val blobCredentialBuilder = DefaultAzureCredentialBuilder()
                config.credential?.authorityHost?.let { blobCredentialBuilder.authorityHost(it) }
                blobContainerClientBuilder.credential(blobCredentialBuilder.build())
                LOG.info("Configured BlobCheckpointStore with DefaultAzureCredential via blobContainerUrl.")
            } else {
                LOG.warn("CheckpointStore blobContainerUrl is set, but EventHub connectionString is also set. " +
                        "Blob access might use implicit credentials if blobContainerUrl does not include a SAS token. " +
                        "Ensure the identity used for EventHubs also has Blob Storage permissions, or provide storage-specific credentials for checkpointing.")
                // If blobContainerUrl includes SAS, it will be used. Otherwise, it might try anonymous or fail if EH conn string is not a full Azure credential.
                // This path might require DefaultAzureCredential or ManagedIdentity if RBAC is used for storage access.
            }
        } else if (!checkpointConfig.storageAccountName.isNullOrBlank() && !checkpointConfig.storageContainerName.isNullOrBlank()) {
            val endpoint = "https://${checkpointConfig.storageAccountName}.blob.core.windows.net/${checkpointConfig.storageContainerName}"
            blobContainerClientBuilder.endpoint(endpoint)
            if (!checkpointConfig.storageConnectionString.isNullOrBlank()) {
                blobContainerClientBuilder.connectionString(checkpointConfig.storageConnectionString)
                LOG.info("Configured BlobCheckpointStore with Storage Connection String.")
            } else if (config.connectionString.isNullOrBlank()) { // Use DefaultAzureCredential for blob if no specific storage conn string AND no EH conn string
                val blobCredentialBuilder = DefaultAzureCredentialBuilder()
                config.credential?.authorityHost?.let { blobCredentialBuilder.authorityHost(it) }
                blobContainerClientBuilder.credential(blobCredentialBuilder.build())
                LOG.info("Configured BlobCheckpointStore with DefaultAzureCredential via storage account name.")
            } else {
                 LOG.warn("CheckpointStore storageAccountName/ContainerName is set, but no specific storage connection string provided, " +
                         "and EventHub connectionString is present. Blob access might use implicit credentials. " +
                         "Ensure the identity used for EventHubs also has Blob Storage permissions, or provide storage-specific credentials.")
            }
        } else {
            throw IllegalArgumentException("Invalid CheckpointStore configuration. Provide blobContainerUrl or storageAccountName/storageContainerName.")
        }

        try {
            blobContainerAsyncClient = blobContainerClientBuilder.buildAsyncClient()
            // Optionally, create the container if it doesn't exist
            // blobContainerAsyncClient.createIfNotExists().block()
            LOG.info("BlobContainerAsyncClient for checkpoint store configured successfully.")
        } catch (e: Exception) {
            LOG.error("Failed to build BlobContainerAsyncClient for checkpoint store", e)
            throw RuntimeException("Failed to initialize BlobContainerAsyncClient for checkpoint store", e)
        }
        return BlobCheckpointStore(blobContainerAsyncClient)
    }


    private fun consumeEventData(eventContext: EventContext) {
        LOG.trace("Processing event from partition ${eventContext.partitionContext.partitionId} with sequence number ${eventContext.eventData.sequenceNumber}")
        try {
            val record = AzureEventHubRecord(eventContext, sourceContext)
            consume(record) // This is PushSource.consume()
            eventContext.updateCheckpoint() // Update checkpoint after successful processing
            LOG.debug("Successfully processed and checkpointed event from partition {} sequenceNumber {}",
                eventContext.partitionContext.partitionId, eventContext.eventData.sequenceNumber)
        } catch (e: Exception) {
            LOG.error(
                "Error processing event from partition {} sequenceNumber {}. Event will likely be reprocessed.",
                eventContext.partitionContext.partitionId, eventContext.eventData.sequenceNumber, e
            )
            // Do not update checkpoint on error, to allow reprocessing
        }
    }

    override fun close() {
        LOG.info("Closing Azure Event Hubs Source.")
        if (::eventProcessorClient.isInitialized) {
            try {
                eventProcessorClient.stop()
                LOG.info("EventProcessorClient stopped.")
            } catch (e: Exception) {
                LOG.error("Error stopping EventProcessorClient", e)
            }
        }
    }

    // Inner class for Pulsar Record implementation
    class AzureEventHubRecord(
        private val eventContext: EventContext,
        private val srcCtx: SourceContext
    ) : Record<ByteArray> {
        private val eventData = eventContext.eventData

        override fun getKey(): Optional<String> = Optional.ofNullable(eventData.partitionKey)

        override fun getValue(): ByteArray = eventData.body

        override fun getEventTime(): Optional<Long> =
            Optional.ofNullable(eventData.enqueuedTime?.toEpochMilli())

        override fun getSequenceId(): Optional<Long> = Optional.of(eventData.sequenceNumber)

        override fun getProperties(): Map<String, String> {
            val props = eventData.properties?.toMutableMap() ?: mutableMapOf()
            props["eventhub_partition_id"] = eventContext.partitionContext.partitionId
            props["eventhub_name"] = eventContext.partitionContext.eventHubName
            props["eventhub_fully_qualified_namespace"] = eventContext.partitionContext.fullyQualifiedNamespace
            eventData.systemProperties?.forEach { (key, value) ->
                props["eventhub_system_$key"] = value.toString()
            }
            return props
        }

        override fun getDestinationTopic(): Optional<String> = Optional.empty() // Use default topic

        override fun ack() {
            // EventProcessorClient handles checkpointing which is analogous to acking.
            // Individual ack on Pulsar record might not directly map here unless custom checkpointing logic.
            LOG.trace("Ack called for Event Hubs record: partition {}, offset {}, seqNo {}",
                eventContext.partitionContext.partitionId, eventData.offset, eventData.sequenceNumber)
        }

        override fun fail() {
            LOG.warn("Fail called for Event Hubs record: partition {}, offset {}, seqNo {}. This typically implies reprocessing from last checkpoint.",
                eventContext.partitionContext.partitionId, eventData.offset, eventData.sequenceNumber)
            // EventProcessorClient will reprocess from the last successful checkpoint if an error occurs
            // during processEvent or if updateCheckpoint isn't called.
        }

        override fun getMessage(): Optional<org.apache.pulsar.client.api.Message<ByteArray>> = Optional.empty()

        override fun getRecordContext(): Optional<org.apache.pulsar.io.core.RecordContext> {
             return Optional.of(
                object : org.apache.pulsar.io.core.RecordContext {
                    override fun getPartitionId(): Optional<String> = Optional.of("eventhub-partition-${eventContext.partitionContext.partitionId}")
                    override fun getRecordSequence(): Optional<Long> = Optional.of(eventData.sequenceNumber)
                    override fun ack() = this@AzureEventHubRecord.ack() // or eventContext.updateCheckpoint() if appropriate here
                    override fun fail() = this@AzureEventHubRecord.fail()
                    override fun getAckFuture(): CompletableFuture<Void> {
                        val future = CompletableFuture<Void>()
                        try {
                            // Simulate checkpoint update or tie to actual async checkpoint if possible
                            eventContext.updateCheckpointAsync().whenComplete { _, ex ->
                                if (ex != null) {
                                    LOG.error("Async checkpoint update failed via getAckFuture for partition {} seqNo {}",
                                        eventContext.partitionContext.partitionId, eventData.sequenceNumber, ex)
                                    future.completeExceptionally(ex)
                                } else {
                                    LOG.debug("Async checkpoint update successful via getAckFuture for partition {} seqNo {}",
                                        eventContext.partitionContext.partitionId, eventData.sequenceNumber)
                                    future.complete(null)
                                }
                            }
                        } catch (e: Exception) {
                            LOG.error("Exception during async checkpoint update via getAckFuture for partition {} seqNo {}",
                                eventContext.partitionContext.partitionId, eventData.sequenceNumber, e)
                            future.completeExceptionally(e)
                        }
                        return future
                    }
                    override fun getNackFuture(): CompletableFuture<Void> {
                        val future = CompletableFuture<Void>()
                        // Nack is more complex; typically means don't checkpoint and allow reprocessing.
                        // For simplicity, just complete it.
                        future.complete(null)
                        return future
                    }
                }
            )
        }
    }
}

// Helper callback classes for EventProcessorClient
class ProcessEvent(
    private val sourceContext: SourceContext,
    private val eventConsumer: Consumer<EventContext>
) : Consumer<EventContext> {
    companion object {
        private val LOG_CALLBACK = LoggerFactory.getLogger(ProcessEvent::class.java)
    }
    override fun accept(eventContext: EventContext) {
        LOG_CALLBACK.debug("ProcessEvent: Received event from partition {}, sequence number {}",
            eventContext.partitionContext.partitionId, eventContext.eventData.sequenceNumber)
        try {
            eventConsumer.accept(eventContext)
        } catch (e: Exception) {
            LOG_CALLBACK.error("ProcessEvent: Unhandled exception during event consumption for partition {}, sequence number {}",
                eventContext.partitionContext.partitionId, eventContext.eventData.sequenceNumber, e)
            // This error should ideally be handled within the eventConsumer logic.
            // If it reaches here, it's an unexpected error.
            // The EventProcessorClient's error handler (processError) will be called for partition-level errors.
        }
    }
}

class ProcessError(private val sourceContext: SourceContext) : Consumer<ErrorContext> {
    companion object {
        private val LOG_CALLBACK = LoggerFactory.getLogger(ProcessError::class.java)
    }
    override fun accept(errorContext: ErrorContext) {
        LOG_CALLBACK.error(
            "Azure Event Hubs Source Error: Partition {}, Operation: {}. Exception: {}",
            errorContext.partitionContext.partitionId,
            errorContext.operation,
            errorContext.throwable.message,
            errorContext.throwable
        )
        // TODO: Implement more sophisticated error handling, e.g., metrics, specific shutdown conditions.
        // sourceContext.recordMetric("eventhub_errors", 1)
    }
}
