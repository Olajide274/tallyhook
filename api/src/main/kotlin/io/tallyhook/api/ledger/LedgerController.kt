// This file exposes tenant-scoped account and transaction HTTP endpoints.
package io.tallyhook.api.ledger

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
@RequestMapping("/v1")
@Suppress("ForbiddenComment")
class LedgerController(
    private val ledger: LedgerApplicationService,
) {
    // TODO: Replace the trusted tenant header with an authenticated tenant context.
    @PostMapping("/accounts")
    fun createAccount(
        @RequestHeader("X-Tenant-Id") tenantId: String,
        @Valid @RequestBody request: CreateAccountRequest,
    ): ResponseEntity<AccountResponse> {
        val response = ledger.createAccount(tenantId, request)
        return ResponseEntity.created(URI.create("/v1/accounts/${response.id}")).body(response)
    }

    @GetMapping("/accounts/{id}")
    fun getAccount(
        @RequestHeader("X-Tenant-Id") tenantId: String,
        @PathVariable id: String,
    ): AccountResponse = ledger.getAccount(tenantId, id)

    @GetMapping("/accounts/{id}/balance")
    fun getBalance(
        @RequestHeader("X-Tenant-Id") tenantId: String,
        @PathVariable id: String,
    ): BalanceResponse = ledger.getBalance(tenantId, id)

    @PostMapping("/transactions")
    fun postTransaction(
        @RequestHeader("X-Tenant-Id") tenantId: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody request: CreateTransactionRequest,
    ): ResponseEntity<TransactionResponse> {
        val result = ledger.postTransaction(tenantId, idempotencyKey, request)
        val response = result.transaction.response()
        val status = if (result.replayed) HttpStatus.OK else HttpStatus.CREATED
        return ResponseEntity.status(status).location(URI.create("/v1/transactions/${response.id}")).body(response)
    }

    @GetMapping("/transactions/{id}")
    fun getTransaction(
        @RequestHeader("X-Tenant-Id") tenantId: String,
        @PathVariable id: String,
    ): TransactionResponse = ledger.getTransaction(tenantId, id)

    @PostMapping("/transactions/{id}/reversals")
    fun reverse(
        @RequestHeader("X-Tenant-Id") tenantId: String,
        @PathVariable id: String,
    ): ResponseEntity<TransactionResponse> {
        val response = ledger.reverse(tenantId, id).transaction.response()
        return ResponseEntity.created(URI.create("/v1/transactions/${response.id}")).body(response)
    }

    @GetMapping("/trial-balance")
    fun getTrialBalance(
        @RequestHeader("X-Tenant-Id") tenantId: String,
    ): TrialBalanceResponse = ledger.trialBalance(tenantId)
}
