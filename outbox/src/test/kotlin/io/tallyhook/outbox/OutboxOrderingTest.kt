// This test verifies relay batches use sequence order and only the earliest aggregate event is eligible.
package io.tallyhook.outbox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class OutboxOrderingTest {
    @Test
    fun `candidate selection keeps earliest event per tenant aggregate`() {
        val rows =
            listOf(
                event(4, "tenant-a", "account-1"),
                event(2, "tenant-a", "account-1"),
                event(3, "tenant-a", "account-2"),
                event(1, "tenant-b", "account-1"),
            )

        val candidates = OutboxOrdering.onlyEarliestPerAggregate(rows)

        assertEquals(listOf(1L, 2L, 3L), candidates.map(OutboxEvent::sequenceId))
    }

    @Test
    fun `claimed events are ordered by sequence`() {
        assertEquals(
            listOf(1L, 2L, 3L),
            OutboxOrdering.inSequence(listOf(event(3), event(1), event(2))).map(OutboxEvent::sequenceId),
        )
    }

    private fun event(
        sequence: Long,
        tenantId: String = "tenant-a",
        aggregateId: String = "aggregate",
    ) = OutboxEvent(
        eventId = UUID.randomUUID(),
        tenantId = tenantId,
        aggregateType = "transaction",
        aggregateId = aggregateId,
        eventType = "test",
        schemaVersion = 1,
        payload = "{}",
        sequenceId = sequence,
        attempts = 0,
        claimToken = UUID.randomUUID(),
    )
}
