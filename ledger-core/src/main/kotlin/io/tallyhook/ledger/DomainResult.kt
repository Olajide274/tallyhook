// This file defines typed domain results and validation errors for ledger operations.
package io.tallyhook.ledger

sealed interface DomainResult<out T> {
    data class Success<T>(
        val value: T,
    ) : DomainResult<T>

    data class Failure(
        val error: LedgerError,
    ) : DomainResult<Nothing>
}

sealed interface LedgerError {
    data class InvalidIdentifier(
        val field: String,
        val reason: String,
    ) : LedgerError

    data class InvalidCurrencyCode(
        val code: String,
    ) : LedgerError

    data class InvalidAmount(
        val amount: Long,
    ) : LedgerError

    data class CurrencyMismatch(
        val expected: Currency,
        val actual: Currency,
    ) : LedgerError

    data class ArithmeticOverflow(
        val operation: String,
        val currency: Currency,
    ) : LedgerError

    data class TooFewEntries(
        val actualCount: Int,
    ) : LedgerError

    data class UnbalancedTransaction(
        val currency: Currency,
        val debits: Long,
        val credits: Long,
    ) : LedgerError

    data class TenantMismatch(
        val expected: TenantId,
        val actual: TenantId,
    ) : LedgerError

    data class AccountNotFound(
        val accountId: AccountId,
    ) : LedgerError

    data class DuplicateAccount(
        val accountId: AccountId,
    ) : LedgerError

    data class DuplicateEntryId(
        val entryId: EntryId,
    ) : LedgerError

    data class InvalidDescription(
        val reason: String,
    ) : LedgerError

    data class DuplicateReversal(
        val transactionId: TransactionId,
    ) : LedgerError

    data class InvalidReversalId(
        val transactionId: TransactionId,
    ) : LedgerError
}
