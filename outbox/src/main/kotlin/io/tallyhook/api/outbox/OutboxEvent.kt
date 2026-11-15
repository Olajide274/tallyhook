// This file defines the versioned JSON envelope persisted and relayed by the outbox.
package io.tallyhook.api.outbox

import java.time.Instant
import java.util.UUID

data class LedgerEventEnvelope<T>(
    val eventId: UUID,
    val eventType: String,
    val schemaVersion: Int,
    val tenantId: String,
    val aggregateType: String,
    val aggregateId: String,
    val occurredAt: Instant,
    val data: T,
)

data class OutboxEventDraft(
    val envelope: LedgerEventEnvelope<*>,
)

data class OutboxEvent(
    val eventId: UUID,
    val tenantId: String,
    val aggregateType: String,
    val aggregateId: String,
    val eventType: String,
    val schemaVersion: Int,
    val payload: String,
    val sequenceId: Long,
    val attempts: Int,
    val claimToken: UUID,
)

data class OutboxBacklog(
    val count: Long,
    val oldestAgeSeconds: Double,
)
