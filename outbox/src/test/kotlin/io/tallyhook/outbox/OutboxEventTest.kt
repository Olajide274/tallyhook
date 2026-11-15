// This test verifies event envelopes serialize minor-unit amounts as JSON integers.
package io.tallyhook.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class OutboxEventTest {
    @Test
    fun `envelope preserves version metadata and integer minor units`() {
        val eventId = UUID.randomUUID()
        val envelope =
            LedgerEventEnvelope(
                eventId = eventId,
                eventType = "ledger.transaction.posted",
                schemaVersion = 1,
                tenantId = "tenant-a",
                aggregateType = "transaction",
                aggregateId = "tx-1",
                occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
                data = mapOf("amountMinor" to 1234L, "currency" to "USD"),
            )
        val mapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
        val json = mapper.readTree(mapper.writeValueAsString(envelope))

        assertEquals(eventId.toString(), json["eventId"].asText())
        assertEquals("ledger.transaction.posted", json["eventType"].asText())
        assertEquals(1, json["schemaVersion"].asInt())
        assertTrue(json["data"]["amountMinor"].isIntegralNumber)
        assertEquals(1234L, json["data"]["amountMinor"].asLong())
    }
}
