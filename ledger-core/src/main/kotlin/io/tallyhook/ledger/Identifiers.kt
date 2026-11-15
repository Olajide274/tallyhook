// This file defines bounded, non-blank identifiers used by the ledger domain.
package io.tallyhook.ledger

private const val ID_MAX_LENGTH = 128
private const val IDEMPOTENCY_MAX_LENGTH = 256
private const val IDEMPOTENCY_FIELD = "idempotencyKey"

@JvmInline
value class TenantId private constructor(
    val value: String,
) {
    companion object {
        fun create(value: String): DomainResult<TenantId> = valid("tenantId", value) { TenantId(it) }
    }
}

@JvmInline
value class AccountId private constructor(
    val value: String,
) {
    companion object {
        fun create(value: String): DomainResult<AccountId> = valid("accountId", value) { AccountId(it) }
    }
}

@JvmInline
value class TransactionId private constructor(
    val value: String,
) {
    companion object {
        fun create(value: String): DomainResult<TransactionId> = createTransactionId(value)

        private fun createTransactionId(value: String) = valid("transactionId", value) { TransactionId(it) }
    }
}

@JvmInline
value class EntryId private constructor(
    val value: String,
) {
    companion object {
        fun create(value: String): DomainResult<EntryId> = valid("entryId", value) { EntryId(it) }
    }
}

@JvmInline
value class IdempotencyKey private constructor(
    val value: String,
) {
    companion object {
        fun create(value: String): DomainResult<IdempotencyKey> = key(value)

        internal fun reversalFor(transactionId: TransactionId): IdempotencyKey = reversalKey(transactionId)

        private fun reversalKey(transactionId: TransactionId) = IdempotencyKey("reversal:${transactionId.value}")

        private fun key(value: String) = valid(IDEMPOTENCY_FIELD, value, IDEMPOTENCY_MAX_LENGTH, ::IdempotencyKey)
    }
}

private fun <T> valid(
    field: String,
    value: String,
    maximumLength: Int = ID_MAX_LENGTH,
    construct: (String) -> T,
): DomainResult<T> {
    val error =
        when {
            value.isBlank() -> LedgerError.InvalidIdentifier(field, "must not be blank")
            value.length > maximumLength ->
                LedgerError.InvalidIdentifier(field, "must not exceed $maximumLength characters")
            else -> null
        }
    return if (error == null) {
        DomainResult.Success(construct(value))
    } else {
        DomainResult.Failure(error)
    }
}
