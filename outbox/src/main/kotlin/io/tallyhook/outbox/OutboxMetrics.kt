// This file registers relay counters and database-backed backlog gauges.
package io.tallyhook.outbox

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
class OutboxMetrics(
    registry: MeterRegistry,
    private val store: OutboxStore,
    private val properties: OutboxProperties,
) {
    val published: Counter = registry.counter("tallyhook.outbox.events.published")
    val failures: Counter = registry.counter("tallyhook.outbox.publish.failures")
    val pastMaxAttempts: Counter = registry.counter("tallyhook.outbox.events.past.max.attempts")

    init {
        registry
            .gauge("tallyhook.outbox.unpublished.backlog", store) { it.backlog().count.toDouble() }
        registry.gauge("tallyhook.outbox.oldest.unpublished.age.seconds", store) {
            it.backlog().oldestAgeSeconds
        }
        registry.gauge("tallyhook.outbox.events.past.max.attempts.current", store) {
            it.eventsPastMaxAttempts(properties.maxAttempts).toDouble()
        }
    }
}
