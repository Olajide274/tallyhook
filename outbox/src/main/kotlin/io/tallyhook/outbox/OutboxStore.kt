// This file claims ordered due events and records relay acknowledgements or retry state.
package io.tallyhook.outbox

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Duration
import java.util.UUID

@Repository
class OutboxStore(
    private val jdbc: JdbcClient,
) {
    fun backlog(): OutboxBacklog {
        val result =
            jdbc
                .sql(
                    """
                    SELECT COUNT(*) AS pending_count,
                           COALESCE(EXTRACT(EPOCH FROM (now() - MIN(created_at))), 0) AS oldest_age
                    FROM outbox_events
                    WHERE published_at IS NULL
                    """.trimIndent(),
                ).query { resultSet, _ ->
                    OutboxBacklog(resultSet.getLong("pending_count"), resultSet.getDouble("oldest_age"))
                }.single()
        return result
    }

    fun eventsPastMaxAttempts(maxAttempts: Int): Long =
        jdbc
            .sql(
                """
                SELECT COUNT(*) FROM outbox_events
                WHERE published_at IS NULL AND attempts >= :maxAttempts
                """.trimIndent(),
            ).param("maxAttempts", maxAttempts)
            .query(Long::class.java)
            .single()

    @Transactional
    fun claimDueBatch(
        batchSize: Int,
        maxAttempts: Int,
        claimLease: Duration,
    ): List<OutboxEvent> {
        val claimToken = UUID.randomUUID()
        return jdbc
            .sql(
                """
                WITH due AS (
                    SELECT event.sequence_id
                    FROM outbox_events event
                    WHERE event.published_at IS NULL
                      AND event.attempts < :maxAttempts
                      AND event.next_attempt_at <= now()
                      AND (event.claim_token IS NULL OR event.claimed_until < now())
                      AND NOT EXISTS (
                          SELECT 1
                          FROM outbox_events earlier
                          WHERE earlier.tenant_id = event.tenant_id
                            AND earlier.aggregate_id = event.aggregate_id
                            AND earlier.sequence_id < event.sequence_id
                            AND earlier.published_at IS NULL
                      )
                    ORDER BY event.sequence_id
                    FOR UPDATE SKIP LOCKED
                    LIMIT :batchSize
                )
                UPDATE outbox_events event
                SET claim_token = :claimToken,
                    claimed_until = now() + (:leaseMillis * INTERVAL '1 millisecond')
                FROM due
                WHERE event.sequence_id = due.sequence_id
                RETURNING event.id, event.tenant_id, event.aggregate_type, event.aggregate_id,
                          event.event_type, event.payload::TEXT AS payload,
                          (event.payload->>'schemaVersion')::INTEGER AS schema_version,
                          event.sequence_id,
                          event.attempts, event.claim_token
                """.trimIndent(),
            ).param("maxAttempts", maxAttempts)
            .param("batchSize", batchSize)
            .param("claimToken", claimToken)
            .param("leaseMillis", claimLease.toMillis())
            .query { resultSet, _ -> mapEvent(resultSet) }
            .list()
            .sortedBy(OutboxEvent::sequenceId)
    }

    fun markPublished(event: OutboxEvent): Boolean =
        jdbc
            .sql(
                """
                UPDATE outbox_events
                SET published_at = now(), claim_token = NULL, claimed_until = NULL, last_error = NULL
                WHERE id = :eventId AND claim_token = :claimToken AND published_at IS NULL
                """.trimIndent(),
            ).param("eventId", event.eventId)
            .param("claimToken", event.claimToken)
            .update() == 1

    fun recordFailure(
        event: OutboxEvent,
        delay: Duration,
        error: String,
    ): Int? =
        jdbc
            .sql(
                """
                UPDATE outbox_events
                SET attempts = attempts + 1,
                    last_error = :error,
                    next_attempt_at = now() + (:delayMillis * INTERVAL '1 millisecond'),
                    claim_token = NULL,
                    claimed_until = NULL
                WHERE id = :eventId AND claim_token = :claimToken AND published_at IS NULL
                RETURNING attempts
                """.trimIndent(),
            ).param("error", error.take(MAX_ERROR_LENGTH))
            .param("delayMillis", delay.toMillis())
            .param("eventId", event.eventId)
            .param("claimToken", event.claimToken)
            .query(Int::class.java)
            .list()
            .firstOrNull()

    fun find(
        tenantId: String,
        eventId: UUID,
    ): OutboxRow? =
        jdbc
            .sql(
                """
                SELECT id, tenant_id, aggregate_type, aggregate_id, event_type, payload::TEXT AS payload,
                       sequence_id, created_at, published_at, attempts, last_error, next_attempt_at
                FROM outbox_events
                WHERE tenant_id = :tenantId AND id = :eventId
                """.trimIndent(),
            ).param("tenantId", tenantId)
            .param("eventId", eventId)
            .query { rs, _ ->
                OutboxRow(
                    eventId = rs.getObject("id", UUID::class.java),
                    tenantId = rs.getString("tenant_id"),
                    aggregateType = rs.getString("aggregate_type"),
                    aggregateId = rs.getString("aggregate_id"),
                    eventType = rs.getString("event_type"),
                    payload = rs.getString("payload"),
                    sequenceId = rs.getLong("sequence_id"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    publishedAt = rs.getTimestamp("published_at")?.toInstant(),
                    attempts = rs.getInt("attempts"),
                    lastError = rs.getString("last_error"),
                    nextAttemptAt = rs.getTimestamp("next_attempt_at").toInstant(),
                )
            }.list()
            .firstOrNull()

    fun requeue(
        tenantId: String,
        eventId: UUID,
    ): Boolean =
        jdbc
            .sql(
                """
                UPDATE outbox_events
                SET attempts = 0, last_error = NULL, next_attempt_at = now(),
                    claim_token = NULL, claimed_until = NULL
                WHERE tenant_id = :tenantId AND id = :eventId
                  AND published_at IS NULL AND attempts > 0
                """.trimIndent(),
            ).param("tenantId", tenantId)
            .param("eventId", eventId)
            .update() == 1

    private fun mapEvent(resultSet: ResultSet): OutboxEvent =
        OutboxEvent(
            eventId = resultSet.getObject("id", UUID::class.java),
            tenantId = resultSet.getString("tenant_id"),
            aggregateType = resultSet.getString("aggregate_type"),
            aggregateId = resultSet.getString("aggregate_id"),
            eventType = resultSet.getString("event_type"),
            schemaVersion = resultSet.getInt("schema_version"),
            payload = resultSet.getString("payload"),
            sequenceId = resultSet.getLong("sequence_id"),
            attempts = resultSet.getInt("attempts"),
            claimToken = resultSet.getObject("claim_token", UUID::class.java),
        )

    private companion object {
        const val MAX_ERROR_LENGTH = 2_000
    }
}

data class OutboxRow(
    val eventId: UUID,
    val tenantId: String,
    val aggregateType: String,
    val aggregateId: String,
    val eventType: String,
    val payload: String,
    val sequenceId: Long,
    val createdAt: java.time.Instant,
    val publishedAt: java.time.Instant?,
    val attempts: Int,
    val lastError: String?,
    val nextAttemptAt: java.time.Instant,
)
