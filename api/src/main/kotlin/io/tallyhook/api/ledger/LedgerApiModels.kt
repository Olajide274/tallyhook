// This file defines the JSON request and response models for the tenant ledger API.
package io.tallyhook.api.ledger

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

data class CreateAccountRequest(
    @field:NotBlank
    @field:Size(max = 255)
    val name: String,
    val type: String,
    val currency: String,
)

data class AccountResponse(
    val id: String,
    val tenantId: String,
    val name: String,
    val type: String,
    val currency: String,
)

data class BalanceResponse(
    val accountId: String,
    val amountMinor: Long,
    val currency: String,
    val normalBalance: String,
)

data class CreateTransactionRequest(
    val description: String,
    val effectiveAt: Instant,
    val entries: List<CreateEntryRequest>,
)

data class CreateEntryRequest(
    val accountId: String,
    val direction: String,
    val amountMinor: Long,
    val currency: String,
)

data class TransactionResponse(
    val id: String,
    val tenantId: String,
    val idempotencyKey: String,
    val description: String,
    val effectiveAt: Instant,
    val reversesTransactionId: String?,
    val entries: List<TransactionEntryResponse>,
)

data class TransactionEntryResponse(
    val id: String,
    val accountId: String,
    val direction: String,
    val amountMinor: Long,
    val currency: String,
)

data class TrialBalanceResponse(
    val totals: List<TrialBalanceCurrencyResponse>,
)

data class TrialBalanceCurrencyResponse(
    val currency: String,
    val debitsMinor: Long,
    val creditsMinor: Long,
)
