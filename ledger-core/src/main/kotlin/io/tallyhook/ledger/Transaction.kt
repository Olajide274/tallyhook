// This file constructs immutable transactions only after validating their ledger invariants.
package io.tallyhook.ledger

import java.time.Instant
import java.util.Collections

@Suppress("LongParameterList")
class Transaction private constructor(
    val id: TransactionId,
    val tenantId: TenantId,
    val idempotencyKey: IdempotencyKey,
    val description: String,
    val effectiveAt: Instant,
    entries: List<Entry>,
    val reversesTransactionId: TransactionId?,
) {
    val entries: List<Entry> = Collections.unmodifiableList(ArrayList(entries))

    companion object {
        @Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList", "ReturnCount")
        fun create(
            id: TransactionId,
            tenantId: TenantId,
            idempotencyKey: IdempotencyKey,
            description: String,
            effectiveAt: Instant,
            entries: Iterable<Entry>,
            accounts: Iterable<Account>,
            reversesTransactionId: TransactionId? = null,
        ): DomainResult<Transaction> {
            if (description.isBlank() || description.length > MAX_DESCRIPTION_LENGTH) {
                return DomainResult.Failure(
                    LedgerError.InvalidDescription("must be non-blank and at most $MAX_DESCRIPTION_LENGTH characters"),
                )
            }

            val transactionEntries = entries.toList()
            if (transactionEntries.size < MINIMUM_ENTRY_COUNT) {
                return DomainResult.Failure(LedgerError.TooFewEntries(transactionEntries.size))
            }

            val accountsById = mutableMapOf<AccountId, Account>()
            for (account in accounts) {
                if (accountsById.putIfAbsent(account.id, account) != null) {
                    return DomainResult.Failure(LedgerError.DuplicateAccount(account.id))
                }
            }

            val entryIds = mutableSetOf<EntryId>()
            val totalsByCurrency = mutableMapOf<Currency, MutableTotals>()
            for (entry in transactionEntries) {
                if (!entryIds.add(entry.id)) {
                    return DomainResult.Failure(LedgerError.DuplicateEntryId(entry.id))
                }
                if (entry.money.minorUnits <= 0) {
                    return DomainResult.Failure(LedgerError.InvalidAmount(entry.money.minorUnits))
                }

                val account =
                    accountsById[entry.accountId]
                        ?: return DomainResult.Failure(LedgerError.AccountNotFound(entry.accountId))
                if (account.tenantId != tenantId) {
                    return DomainResult.Failure(LedgerError.TenantMismatch(tenantId, account.tenantId))
                }
                if (entry.money.currency != account.currency) {
                    return DomainResult.Failure(LedgerError.CurrencyMismatch(account.currency, entry.money.currency))
                }

                val totals = totalsByCurrency.getOrPut(entry.money.currency) { MutableTotals() }
                val total = if (entry.direction == Direction.DEBIT) totals.debits else totals.credits
                val updatedTotal =
                    try {
                        Math.addExact(total, entry.money.minorUnits)
                    } catch (_: ArithmeticException) {
                        return DomainResult.Failure(
                            LedgerError.ArithmeticOverflow(
                                "${entry.direction.name.lowercase()} total",
                                entry.money.currency,
                            ),
                        )
                    }
                if (entry.direction == Direction.DEBIT) {
                    totals.debits = updatedTotal
                } else {
                    totals.credits = updatedTotal
                }
            }

            for ((currency, totals) in totalsByCurrency) {
                if (totals.debits != totals.credits) {
                    return DomainResult.Failure(
                        LedgerError.UnbalancedTransaction(currency, totals.debits, totals.credits),
                    )
                }
            }

            return DomainResult.Success(
                Transaction(
                    id,
                    tenantId,
                    idempotencyKey,
                    description,
                    effectiveAt,
                    transactionEntries,
                    reversesTransactionId,
                ),
            )
        }

        private const val MINIMUM_ENTRY_COUNT = 2
        private const val MAX_DESCRIPTION_LENGTH = 1_024
    }

    private class MutableTotals(
        var debits: Long = 0,
        var credits: Long = 0,
    )
}
