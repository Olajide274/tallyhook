// This test-only fake stores idempotency results in memory without persistence.
package io.tallyhook.ledger

class FakeIdempotencyPort : IdempotencyPort {
    private val results = mutableMapOf<Pair<TenantId, IdempotencyKey>, TransactionId>()

    override fun lookup(
        tenantId: TenantId,
        key: IdempotencyKey,
    ): IdempotencyLookup =
        results[tenantId to key]
            ?.let(IdempotencyLookup::Seen)
            ?: IdempotencyLookup.NotSeen

    fun remember(
        tenantId: TenantId,
        key: IdempotencyKey,
        transactionId: TransactionId,
    ) {
        results[tenantId to key] = transactionId
    }
}
