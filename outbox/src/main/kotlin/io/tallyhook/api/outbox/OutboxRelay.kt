// This file relays committed outbox rows at least once; consumers must deduplicate eventId.
package io.tallyhook.api.outbox

import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.springframework.dao.DataAccessException
import org.springframework.kafka.KafkaException
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Component
class OutboxRelay(
    private val store: OutboxStore,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val properties: OutboxProperties,
    private val metrics: OutboxMetrics,
) {
    private val backoff =
        RetryBackoff(
            properties.baseBackoff,
            properties.maxBackoff,
            properties.backoffJitter,
        )

    @Scheduled(
        fixedDelayString = "\${tallyhook.outbox.poll-interval:1000ms}",
        initialDelayString = "\${tallyhook.outbox.initial-delay:1000ms}",
    )
    fun scheduledPoll() {
        if (properties.enabled) {
            pollOnce()
        }
    }

    fun pollOnce(afterBrokerAcknowledged: (OutboxEvent) -> Unit = {}): Int {
        if (!properties.enabled) {
            return 0
        }
        val batch = store.claimDueBatch(properties.batchSize, properties.maxAttempts, properties.claimLease)
        batch.forEach { event -> publish(event, afterBrokerAcknowledged) }
        return batch.size
    }

    private fun publish(
        event: OutboxEvent,
        afterBrokerAcknowledged: (OutboxEvent) -> Unit,
    ) {
        try {
            val record =
                ProducerRecord(
                    properties.topic,
                    null,
                    "${event.tenantId}:${event.aggregateId}",
                    event.payload,
                )
            record.headers().add(header("eventId", event.eventId.toString()))
            record.headers().add(header("eventType", event.eventType))
            record.headers().add(header("schemaVersion", event.schemaVersion.toString()))
            kafkaTemplate
                .send(record)
                .get(properties.sendTimeout.toMillis(), TimeUnit.MILLISECONDS)
            afterBrokerAcknowledged(event)
            check(store.markPublished(event)) { "Outbox claim for ${event.eventId} was lost before acknowledgement." }
            metrics.published.increment()
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            recordFailure(event, exception)
        } catch (exception: ExecutionException) {
            recordFailure(event, exception)
        } catch (exception: TimeoutException) {
            recordFailure(event, exception)
        } catch (exception: KafkaException) {
            recordFailure(event, exception)
        } catch (exception: DataAccessException) {
            recordFailure(event, exception)
        } catch (exception: IllegalStateException) {
            recordFailure(event, exception)
        }
    }

    private fun recordFailure(
        event: OutboxEvent,
        exception: Exception,
    ) {
        metrics.failures.increment()
        val attempt = event.attempts + 1
        val delay = backoff.delayAfterFailure(attempt)
        val message = exception.message ?: exception.javaClass.simpleName
        val updatedAttempt = store.recordFailure(event, delay, message)
        if (updatedAttempt != null && updatedAttempt >= properties.maxAttempts) {
            metrics.pastMaxAttempts.increment()
        }
    }

    private fun header(
        key: String,
        value: String,
    ) = RecordHeader(key, value.toByteArray(StandardCharsets.UTF_8))
}
