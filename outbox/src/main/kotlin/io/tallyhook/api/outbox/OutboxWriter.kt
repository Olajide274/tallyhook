// This file persists event envelopes in the caller's current JDBC transaction.
package io.tallyhook.api.outbox

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.Timestamp

@Component
class OutboxWriter(
    private val jdbc: JdbcClient,
    private val objectMapper: ObjectMapper,
) {
    fun append(draft: OutboxEventDraft) {
        val envelope = draft.envelope
        val payload =
            try {
                objectMapper.writeValueAsString(envelope)
            } catch (exception: JsonProcessingException) {
                throw IllegalArgumentException("Could not serialize outbox event ${envelope.eventId}.", exception)
            }
        jdbc
            .sql(
                """
                INSERT INTO outbox_events
                    (id, tenant_id, aggregate_type, aggregate_id, event_type, payload, created_at)
                VALUES
                    (:eventId, :tenantId, :aggregateType, :aggregateId, :eventType, CAST(:payload AS JSONB), :createdAt)
                """.trimIndent(),
            ).param("eventId", envelope.eventId)
            .param("tenantId", envelope.tenantId)
            .param("aggregateType", envelope.aggregateType)
            .param("aggregateId", envelope.aggregateId)
            .param("eventType", envelope.eventType)
            .param("payload", payload)
            .param("createdAt", Timestamp.from(envelope.occurredAt))
            .update()
    }
}
