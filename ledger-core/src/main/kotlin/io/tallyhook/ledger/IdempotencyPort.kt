// This file declares the domain port for looking up idempotent transaction results.
package io.tallyhook.ledger

sealed interface IdempotencyLookup {
    data object NotSeen : IdempotencyLookup

    data class Seen(
        val transactionId: TransactionId,
    ) : IdempotencyLookup
}

interface IdempotencyPort {
    fun lookup(
        tenantId: TenantId,
        key: IdempotencyKey,
    ): IdempotencyLookup
}
