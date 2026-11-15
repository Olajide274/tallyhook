// This file maps typed ledger failures to safe HTTP problem responses.
package io.tallyhook.api.ledger

import io.tallyhook.ledger.LedgerError
import org.springframework.http.HttpStatus

class LedgerApiException(
    val status: HttpStatus,
    val code: String,
    val detail: String,
) : RuntimeException(detail) {
    companion object {
        @Suppress("CyclomaticComplexMethod", "LongMethod")
        fun from(error: LedgerError): LedgerApiException =
            when (error) {
                is LedgerError.AccountNotFound ->
                    LedgerApiException(
                        HttpStatus.NOT_FOUND,
                        "account_not_found",
                        "The requested account was not found.",
                    )
                is LedgerError.ArithmeticOverflow ->
                    LedgerApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "arithmetic_overflow",
                        "The amount exceeds supported limits.",
                    )
                is LedgerError.CurrencyMismatch ->
                    LedgerApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "currency_mismatch",
                        "Entry currency must match its account.",
                    )
                is LedgerError.DuplicateAccount ->
                    LedgerApiException(HttpStatus.CONFLICT, "duplicate_account", "The account already exists.")
                is LedgerError.DuplicateEntryId ->
                    LedgerApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "duplicate_entry",
                        "Entry identifiers must be unique.",
                    )
                is LedgerError.DuplicateReversal ->
                    LedgerApiException(
                        HttpStatus.CONFLICT,
                        "duplicate_reversal",
                        "The transaction has already been reversed.",
                    )
                is LedgerError.InvalidAmount ->
                    LedgerApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "invalid_amount",
                        "Entry amounts must be positive minor units.",
                    )
                is LedgerError.InvalidCurrencyCode ->
                    LedgerApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "invalid_currency",
                        "The currency code is not a supported ISO 4217 code.",
                    )
                is LedgerError.InvalidDescription ->
                    LedgerApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "invalid_description",
                        "The transaction description is invalid.",
                    )
                is LedgerError.InvalidIdentifier ->
                    LedgerApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "invalid_identifier",
                        "The ${error.field} is invalid.",
                    )
                is LedgerError.InvalidReversalId ->
                    LedgerApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "invalid_reversal_id",
                        "A reversal must have a new transaction identifier.",
                    )
                is LedgerError.TenantMismatch ->
                    LedgerApiException(
                        HttpStatus.NOT_FOUND,
                        "resource_not_found",
                        "The requested resource was not found.",
                    )
                is LedgerError.TooFewEntries ->
                    LedgerApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "too_few_entries",
                        "A transaction requires at least two entries.",
                    )
                is LedgerError.UnbalancedTransaction ->
                    LedgerApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "unbalanced_transaction",
                        "Transaction debits and credits must balance per currency.",
                    )
            }
    }
}
