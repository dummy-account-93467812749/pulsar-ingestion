package org.apache.pulsar.io.kafka.source

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.pulsar.functions.api.Record
import org.apache.pulsar.io.core.PushSource
import org.apache.pulsar.io.core.SourceContext
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class KafkaSource : PushSource<ByteArray>() {
    private lateinit var config: KafkaSourceConfig

    // Made internal for testing purposes to allow injection/verification
    internal lateinit var kafkaConsumer: KafkaConsumer<Any, Any>
    private lateinit var consumerThread: ExecutorService
    private val running = AtomicBoolean(false)

    companion object {
        private val LOG = LoggerFactory.getLogger(KafkaSource::class.java)
    }

    // Internal constructor for testing to inject a mock consumer
    internal constructor(consumer: KafkaConsumer<Any, Any>) : this() {
        this.kafkaConsumer = consumer
    }

    override fun open(configMap: Map<String, Any>, sourceContext: SourceContext) {
        LOG.info("Opening KafkaSource with config: {}", configMap)
        config = KafkaSourceConfig.load(configMap)

        if (config.topic.isBlank()) {
            throw IllegalArgumentException("Kafka topic must be configured.")
        }

        if (!::kafkaConsumer.isInitialized) { // Initialize only if not already injected for testing
            val props = config.toProperties()
            // Ensure necessary consumer properties are set if not in additionalProperties
            props.putIfAbsent(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, config.maxPollRecords.toString())
            // Add any other essential defaults here

            try {
                kafkaConsumer = KafkaConsumer(props)
                LOG.info("KafkaConsumer created successfully with properties: {}", props)
            } catch (e: Exception) {
                LOG.error("Failed to create KafkaConsumer", e)
                throw RuntimeException("Failed to create KafkaConsumer", e)
            }
        }

        running.set(true)
        consumerThread = Executors.newSingleThreadExecutor { r ->
            Thread(r, "kafka-source-consumer-${config.topic}-${sourceContext.instanceId}")
        }

        consumerThread.submit {
            try {
                LOG.info("Subscribing to Kafka topic: {}", config.topic)
                kafkaConsumer.subscribe(listOf(config.topic))

                while (running.get()) {
                    try {
                        val consumerRecords = kafkaConsumer.poll(Duration.ofMillis(config.pollIntervalMs))
                        if (consumerRecords.isEmpty && LOG.isTraceEnabled) {
                            LOG.trace("No records received from Kafka topic {}", config.topic)
                        } else {
                            LOG.debug("Polled {} records from Kafka topic {}", consumerRecords.count(), config.topic)
                        }

                        for (record in consumerRecords) {
                            if (!running.get()) break
                            processRecord(record, sourceContext)
                        }
                        // Manual commit if auto-commit is disabled, though default is true
                        // if (props[ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG] == "false") {
                        //    kafkaConsumer.commitAsync();
                        // }
                    } catch (e: org.apache.kafka.common.errors.WakeupException) {
                        LOG.info("KafkaConsumer poll wakeup received, shutting down polling for topic {}", config.topic)
                        // Ignore, this is expected on shutdown
                    } catch (e: Exception) {
                        LOG.error("Error polling from Kafka topic ${config.topic}", e)
                        // Decide on error handling: stop, retry, etc.
                        // For now, just log and continue polling, or consider stopping.
                    }
                }
            } catch (e: Exception) {
                LOG.error("Consumer thread for topic ${config.topic} encountered an unrecoverable error.", e)
            } finally {
                LOG.info("Closing KafkaConsumer for topic {}", config.topic)
                try {
                    kafkaConsumer.close(Duration.ofSeconds(5))
                } catch (closeE: Exception) {
                    LOG.error("Error closing KafkaConsumer for topic ${config.topic}", closeE)
                }
                LOG.info("Consumer thread for topic {} finished.", config.topic)
            }
        }
        LOG.info("KafkaSource started successfully for topic {}", config.topic)
    }

    private fun processRecord(consumerRecord: ConsumerRecord<Any, Any>, sourceContext: SourceContext) {
        try {
            // Assuming value is byte[] due to common usage with ByteArrayDeserializer
            // If not, casting or conversion is needed based on actual valueDeserializer
            val valueBytes = when (val value = consumerRecord.value()) {
                is ByteArray -> value
                is String -> value.toByteArray(Charsets.UTF_8)
                // Add other type handlers as necessary, or throw exception for unexpected types
                else -> {
                    LOG.warn(
                        "Received record with unexpected value type: ${value?.javaClass?.name} for topic ${config.topic}. " +
                            "Ensure valueDeserializer produces byte[] or a convertible type.",
                    )
                    // Attempt to convert via toString().getBytes() as a fallback, or handle as an error.
                    value?.toString()?.toByteArray(Charsets.UTF_8)
                }
            }

            if (valueBytes == null) {
                LOG.warn("Received null value from Kafka record on topic {}, skipping.", config.topic)
                return
            }

            val record = KafkaPulsarRecord(consumerRecord, valueBytes, sourceContext)
            consume(record)
            LOG.trace("Consumed record from topic {} - offset {}", consumerRecord.topic(), consumerRecord.offset())
        } catch (e: Exception) {
            LOG.error(
                "Error processing record from Kafka topic {} at offset {}",
                consumerRecord.topic(),
                consumerRecord.offset(),
                e,
            )
        }
    }

    override fun close() {
        LOG.info("Closing KafkaSource for topic {}", config.topic)
        running.set(false)
        kafkaConsumer.wakeup() // Interrupt polling
        consumerThread.shutdown()
        try {
            if (!consumerThread.awaitTermination(10, TimeUnit.SECONDS)) {
                LOG.warn(
                    "Consumer thread for topic {} did not terminate in time, forcing shutdown.",
                    config.topic,
                )
                consumerThread.shutdownNow()
            }
        } catch (e: InterruptedException) {
            LOG.warn("Interrupted while waiting for consumer thread for topic {} to terminate.", config.topic)
            consumerThread.shutdownNow()
            Thread.currentThread().interrupt()
        }
        LOG.info("KafkaSource closed for topic {}", config.topic)
    }

    // Inner class for Pulsar Record implementation
    class KafkaPulsarRecord(
        private val consumerRecord: ConsumerRecord<Any, Any>,
        private val recordValue: ByteArray,
        private val srcCtx: SourceContext,
    ) : Record<ByteArray> {

        override fun getKey(): Optional<String> {
            // Kafka key can be null, or not a String if keyDeserializer is different
            return Optional.ofNullable(consumerRecord.key()?.toString())
        }

        override fun getValue(): ByteArray = recordValue

        override fun getEventTime(): Optional<Long> {
            // Kafka records have a timestamp
            return if (consumerRecord.timestamp() >= 0) Optional.of(consumerRecord.timestamp()) else Optional.empty()
        }

        override fun getSequenceId(): Optional<Long> {
            return Optional.of(consumerRecord.offset())
        }

        override fun getProperties(): Map<String, String> {
            val props = mutableMapOf<String, String>()
            props["kafka_topic"] = consumerRecord.topic()
            props["kafka_partition"] = consumerRecord.partition().toString()
            props["kafka_offset"] = consumerRecord.offset().toString()
            props["kafka_timestamp_type"] = consumerRecord.timestampType().name
            consumerRecord.headers()?.forEach { header ->
                props["kafka_header_${header.key()}"] = String(header.value(), Charsets.UTF_8)
            }
            return props
        }

        override fun getDestinationTopic(): Optional<String> {
            // By default, let Pulsar handle routing or use default topic from context if needed
            return Optional.empty()
        }

        override fun ack() {
            // In Kafka, offsets are typically committed by the consumer group,
            // either automatically or manually via kafkaConsumer.commitSync/Async.
            // Individual record acking like in Pulsar is not directly analogous here
            // unless special handling for at-least-once processing with manual commits is implemented.
            // For a simple source, this might be a no-op if auto-commit is enabled.
            // If manual commit is used, logic would be more complex here or in the polling loop.
            LOG.debug("Ack for Kafka record from topic {}, offset {}", consumerRecord.topic(), consumerRecord.offset())
        }

        override fun fail() {
            // Similar to ack, Kafka's failure handling is different.
            // Typically, if processing fails, the offset is not committed,
            // and the record might be re-polled after a timeout or based on group rebalance.
            // Specific error handling (e.g., sending to a dead-letter queue) would be custom.
            LOG.warn("Fail for Kafka record from topic {}, offset {}", consumerRecord.topic(), consumerRecord.offset())
            // Depending on strategy, could trigger a re-seek or other error handling.
        }

        override fun getMessage(): Optional<org.apache.pulsar.client.api.Message<ByteArray>> {
            // This method is for Pulsar client messages, typically not used by sources directly.
            return Optional.empty()
        }

        override fun getRecordContext(): Optional<org.apache.pulsar.io.core.RecordContext> {
            // Provide a basic RecordContext
            return Optional.of(
                object : org.apache.pulsar.io.core.RecordContext {
                    override fun getPartitionId(): Optional<String> = Optional.of("partition-${consumerRecord.partition()}")
                    override fun getRecordSequence(): Optional<Long> = Optional.of(consumerRecord.offset())
                    override fun ack() = this@KafkaPulsarRecord.ack()
                    override fun fail() = this@KafkaPulsarRecord.fail()
                    override fun getAckFuture(): CompletableFuture<Void> {
                        val future = CompletableFuture<Void>()
                        // Simulate ack completion; in real manual commit, this would be tied to Kafka commit callback
                        srcCtx.recordMetric("kafka_ack_simulated", 1)
                        future.complete(null)
                        return future
                    }
                    override fun getNackFuture(): CompletableFuture<Void> {
                        val future = CompletableFuture<Void>()
                        // Simulate nack completion
                        srcCtx.recordMetric("kafka_nack_simulated", 1)
                        future.complete(null) // Or complete exceptionally for actual failure propagation
                        return future
                    }
                },
            )
        }
    }
}
