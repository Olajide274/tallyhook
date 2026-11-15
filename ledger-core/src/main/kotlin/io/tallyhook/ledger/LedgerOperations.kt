// This file provides pure reversal, balance, and trial-balance calculations.
package io.tallyhook.ledger

import java.time.Instant

@Suppress("ReturnCount")
fun reverseTransaction(
    original: Transaction,
    newId: TransactionId,
    effectiveAt: Instant,
    accounts: Iterable<Account>,
    alreadyReversedTransactionIds: Set<TransactionId>,
): DomainResult<Transaction> {
    if (original.id in alreadyReversedTransactionIds) {
        return DomainResult.Failure(LedgerError.DuplicateReversal(original.id))
    }
    if (newId == original.id) {
        return DomainResult.Failure(LedgerError.InvalidReversalId(newId))
    }

    return Transaction.create(
        id = newId,
        tenantId = original.tenantId,
        idempotencyKey = IdempotencyKey.reversalFor(original.id),
        description = "Reversal of ${original.id.value}",
        effectiveAt = effectiveAt,
        entries = original.entries.map(Entry::reversed),
        accounts = accounts,
        reversesTransactionId = original.id,
    )
}

@Suppress("ReturnCount")
fun balance(
    account: Account,
    entries: Iterable<Entry>,
): DomainResult<Money> {
    var balance = Money.zero(account.currency)
    for (entry in entries) {
        if (entry.accountId != account.id) {
            continue
        }
        if (entry.money.currency != account.currency) {
            return DomainResult.Failure(LedgerError.CurrencyMismatch(account.currency, entry.money.currency))
        }

        val amount =
            if (entry.direction == account.type.normalBalance) {
                entry.money
            } else {
                when (val negated = entry.money.negate()) {
                    is DomainResult.Success -> negated.value
                    is DomainResult.Failure -> return negated
                }
            }
        when (val sum = balance.plus(amount)) {
            is DomainResult.Success -> balance = sum.value
            is DomainResult.Failure -> return sum
        }
    }
    return DomainResult.Success(balance)
}

@Suppress("ReturnCount")
fun trialBalance(
    tenantId: TenantId,
    accounts: Iterable<Account>,
    entries: Iterable<Entry>,
): DomainResult<Unit> {
    val accountsById = mutableMapOf<AccountId, Account>()
    for (account in accounts) {
        if (account.tenantId != tenantId) {
            return DomainResult.Failure(LedgerError.TenantMismatch(tenantId, account.tenantId))
        }
        if (accountsById.putIfAbsent(account.id, account) != null) {
            return DomainResult.Failure(LedgerError.DuplicateAccount(account.id))
        }
    }

    val totalsByCurrency = mutableMapOf<Currency, MutableTotals>()
    for (entry in entries) {
        if (entry.money.minorUnits <= 0) {
            return DomainResult.Failure(LedgerError.InvalidAmount(entry.money.minorUnits))
        }
        val account =
            accountsById[entry.accountId]
                ?: return DomainResult.Failure(LedgerError.AccountNotFound(entry.accountId))
        if (account.currency != entry.money.currency) {
            return DomainResult.Failure(LedgerError.CurrencyMismatch(account.currency, entry.money.currency))
        }
        val totals = totalsByCurrency.getOrPut(entry.money.currency) { MutableTotals() }
        val total = if (entry.direction == Direction.DEBIT) totals.debits else totals.credits
        val updatedTotal =
            try {
                Math.addExact(total, entry.money.minorUnits)
            } catch (_: ArithmeticException) {
                return DomainResult.Failure(
                    LedgerError.ArithmeticOverflow("${entry.direction.name.lowercase()} total", entry.money.currency),
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
            return DomainResult.Failure(LedgerError.UnbalancedTransaction(currency, totals.debits, totals.credits))
        }
    }
    return DomainResult.Success(Unit)
}

private class MutableTotals(
    var debits: Long = 0,
    var credits: Long = 0,
)
