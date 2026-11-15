// This file defines deterministic sequence ordering for a claimed outbox batch.
package io.tallyhook.outbox

object OutboxOrdering {
    fun inSequence(events: Iterable<OutboxEvent>): List<OutboxEvent> = events.sortedBy(OutboxEvent::sequenceId)

    fun onlyEarliestPerAggregate(events: Iterable<OutboxEvent>): List<OutboxEvent> =
        events
            .groupBy { it.tenantId to it.aggregateId }
            .values
            .map { aggregateEvents -> aggregateEvents.minBy(OutboxEvent::sequenceId) }
            .sortedBy(OutboxEvent::sequenceId)
}
