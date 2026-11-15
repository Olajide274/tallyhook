// This file coordinates ledger-core validation, tenant-scoped persistence, and idempotent posting.
package io.tallyhook.api.ledger

import io.tallyhook.ledger.Account
import io.tallyhook.ledger.AccountId
import io.tallyhook.ledger.AccountType
import io.tallyhook.ledger.Currency
import io.tallyhook.ledger.Direction
import io.tallyhook.ledger.DomainResult
import io.tallyhook.ledger.Entry
import io.tallyhook.ledger.EntryId
import io.tallyhook.ledger.IdempotencyKey
import io.tallyhook.ledger.IdempotencyLookup
import io.tallyhook.ledger.LedgerError
import io.tallyhook.ledger.Money
import io.tallyhook.ledger.TenantId
import io.tallyhook.ledger.Transaction
import io.tallyhook.ledger.TransactionId
import io.tallyhook.ledger.reverseTransaction
import io.tallyhook.outbox.LedgerEventEnvelope
import io.tallyhook.outbox.OutboxEventDraft
import io.tallyhook.outbox.OutboxWriter
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

data class PostedTransaction(
    val transaction: Transaction,
    val replayed: Boolean,
)

@Service
class LedgerApplicationService(
    private val accountRepository: AccountRepository,
    private val ledgerRepository: LedgerRepository,
    private val idempotencyRepository: PostgresIdempotencyRepository,
    private val outboxWriter: OutboxWriter,
    private val clock: Clock,
) {
    @Transactional
    fun createAccount(
        tenantValue: String,
        request: CreateAccountRequest,
    ): AccountResponse {
        val tenantId = required(TenantId.create(tenantValue))
        val type =
            AccountType.entries.firstOrNull { it.name == request.type }
                ?: invalidRequest("Account type must be one of ASSET, LIABILITY, EQUITY, REVENUE, or EXPENSE.")
        val currency = required(Currency.create(request.currency))
        val accountId = required(AccountId.create(UUID.randomUUID().toString()))
        val account = Account.create(accountId, tenantId, type, currency)
        accountRepository.insert(account, request.name)
        outboxWriter.append(
            OutboxEventDraft(
                LedgerEventEnvelope(
                    eventId = UUID.randomUUID(),
                    eventType = "ledger.account.created",
                    schemaVersion = 1,
                    tenantId = tenantId.value,
                    aggregateType = "account",
                    aggregateId = account.id.value,
                    occurredAt = clock.instant(),
                    data =
                        mapOf(
                            "accountId" to account.id.value,
                            "name" to request.name,
                            "type" to account.type.name,
                            "currency" to account.currency.code,
                        ),
                ),
            ),
        )
        return AccountRecord(account, request.name).response()
    }

    @Transactional(readOnly = true)
    fun getAccount(
        tenantValue: String,
        accountValue: String,
    ): AccountResponse {
        val tenantId = required(TenantId.create(tenantValue))
        val accountId = required(AccountId.create(accountValue))
        return accountRepository.find(tenantId, accountId)?.response() ?: notFound()
    }

    @Transactional(readOnly = true)
    fun getBalance(
        tenantValue: String,
        accountValue: String,
    ): BalanceResponse {
        val tenantId = required(TenantId.create(tenantValue))
        val accountId = required(AccountId.create(accountValue))
        val account =
            accountRepository.find(tenantId, accountId)?.account
                ?: notFound()
        val (debits, credits) = ledgerRepository.balanceTotals(tenantId, accountId, account.currency)
        val debitMoney = Money.of(debits, account.currency)
        val creditMoney = Money.of(credits, account.currency)
        val balance =
            if (account.type.normalBalance == Direction.DEBIT) {
                required(debitMoney.minus(creditMoney))
            } else {
                required(creditMoney.minus(debitMoney))
            }
        return BalanceResponse(
            accountId = account.id.value,
            amountMinor = balance.minorUnits,
            currency = account.currency.code,
            normalBalance = account.type.normalBalance.name,
        )
    }

    @Transactional
    @Suppress("ReturnCount")
    fun postTransaction(
        tenantValue: String,
        idempotencyValue: String,
        request: CreateTransactionRequest,
    ): PostedTransaction {
        val tenantId = required(TenantId.create(tenantValue))
        val key = required(IdempotencyKey.create(idempotencyValue))
        val requestHash = canonicalHash(tenantId, request)

        when (idempotencyRepository.lookup(tenantId, key)) {
            IdempotencyLookup.NotSeen -> Unit
            is IdempotencyLookup.Seen -> return replayOrConflict(tenantId, key, requestHash)
        }

        val transactionId = required(TransactionId.create(UUID.randomUUID().toString()))
        if (!idempotencyRepository.reserve(tenantId, key, requestHash, transactionId)) {
            return replayOrConflict(tenantId, key, requestHash)
        }

        val accountIds = request.entries.map { required(AccountId.create(it.accountId)) }.distinct()
        val accountModels = accountRepository.findByIds(tenantId, accountIds).map(AccountRecord::account)
        val entries =
            request.entries.map { entryRequest ->
                val accountId = required(AccountId.create(entryRequest.accountId))
                val direction =
                    Direction.entries.firstOrNull { it.name == entryRequest.direction }
                        ?: invalidRequest("Entry direction must be DEBIT or CREDIT.")
                val currency = required(Currency.create(entryRequest.currency))
                val entryId = required(EntryId.create(UUID.randomUUID().toString()))
                required(
                    Entry.create(
                        id = entryId,
                        accountId = accountId,
                        direction = direction,
                        money = Money.of(entryRequest.amountMinor, currency),
                    ),
                )
            }
        val transaction =
            required(
                Transaction.create(
                    id = transactionId,
                    tenantId = tenantId,
                    idempotencyKey = key,
                    description = request.description,
                    effectiveAt = request.effectiveAt,
                    entries = entries,
                    accounts = accountModels,
                ),
            )

        ledgerRepository.insert(transaction)
        outboxWriter.append(transactionEvent(transaction, "ledger.transaction.posted", transaction.id.value))
        return PostedTransaction(transaction, replayed = false)
    }

    @Transactional(readOnly = true)
    fun getTransaction(
        tenantValue: String,
        transactionValue: String,
    ): TransactionResponse {
        val tenantId = required(TenantId.create(tenantValue))
        val transactionId = required(TransactionId.create(transactionValue))
        val transaction = ledgerRepository.find(tenantId, transactionId) ?: notFound()
        return transaction.response()
    }

    @Transactional
    fun reverse(
        tenantValue: String,
        transactionValue: String,
    ): PostedTransaction {
        val tenantId = required(TenantId.create(tenantValue))
        val originalId = required(TransactionId.create(transactionValue))
        val original = ledgerRepository.find(tenantId, originalId) ?: notFound()
        if (ledgerRepository.hasReversal(tenantId, originalId)) {
            ledgerFailure(LedgerError.DuplicateReversal(originalId))
        }

        val accountModels =
            accountRepository
                .findByIds(tenantId, original.entries.map(Entry::accountId).distinct())
                .map(AccountRecord::account)
        val newId = required(TransactionId.create(UUID.randomUUID().toString()))
        val reversal =
            required(
                reverseTransaction(
                    original = original,
                    newId = newId,
                    effectiveAt = clock.instant(),
                    accounts = accountModels,
                    alreadyReversedTransactionIds = emptySet(),
                ),
            )
        val requestHash = canonicalReversalHash(tenantId, originalId)
        if (!idempotencyRepository.reserve(tenantId, reversal.idempotencyKey, requestHash, newId)) {
            ledgerFailure(LedgerError.DuplicateReversal(originalId))
        }
        ledgerRepository.insert(reversal)
        outboxWriter.append(
            transactionEvent(
                reversal,
                "ledger.transaction.reversed",
                original.id.value,
            ),
        )
        return PostedTransaction(reversal, replayed = false)
    }

    @Transactional(readOnly = true)
    fun trialBalance(tenantValue: String): TrialBalanceResponse {
        val tenantId = required(TenantId.create(tenantValue))
        return TrialBalanceResponse(
            ledgerRepository.trialBalance(tenantId).map {
                TrialBalanceCurrencyResponse(it.currency.code, it.debitsMinor, it.creditsMinor)
            },
        )
    }

    private fun replayOrConflict(
        tenantId: TenantId,
        key: IdempotencyKey,
        requestHash: String,
    ): PostedTransaction {
        val stored =
            idempotencyRepository.findRecord(tenantId, key)
                ?: error("Idempotency reservation disappeared during posting.")
        if (stored.requestHash != requestHash) {
            throw LedgerApiException(
                HttpStatus.CONFLICT,
                "idempotency_conflict",
                "The idempotency key was already used with a different request.",
            )
        }
        val original = ledgerRepository.find(tenantId, stored.transactionId) ?: notFound()
        return PostedTransaction(original, replayed = true)
    }

    private fun transactionEvent(
        transaction: Transaction,
        eventType: String,
        aggregateId: String,
    ): OutboxEventDraft =
        OutboxEventDraft(
            LedgerEventEnvelope(
                eventId = UUID.randomUUID(),
                eventType = eventType,
                schemaVersion = 1,
                tenantId = transaction.tenantId.value,
                aggregateType = "transaction",
                aggregateId = aggregateId,
                occurredAt = clock.instant(),
                data =
                    mapOf(
                        "transactionId" to transaction.id.value,
                        "description" to transaction.description,
                        "effectiveAt" to transaction.effectiveAt,
                        "reversesTransactionId" to transaction.reversesTransactionId?.value,
                        "entries" to
                            transaction.entries.map { entry ->
                                mapOf(
                                    "entryId" to entry.id.value,
                                    "accountId" to entry.accountId.value,
                                    "direction" to entry.direction.name,
                                    "amountMinor" to entry.money.minorUnits,
                                    "currency" to entry.money.currency.code,
                                )
                            },
                    ),
            ),
        )
}

private fun canonicalHash(
    tenantId: TenantId,
    request: CreateTransactionRequest,
): String =
    sha256 {
        writeUTF(tenantId.value)
        writeUTF(request.description)
        writeUTF(request.effectiveAt.toString())
        writeInt(request.entries.size)
        request.entries.forEach { entry ->
            writeUTF(entry.accountId)
            writeUTF(entry.direction)
            writeLong(entry.amountMinor)
            writeUTF(entry.currency)
        }
    }

private fun canonicalReversalHash(
    tenantId: TenantId,
    originalId: TransactionId,
): String =
    sha256 {
        writeUTF(tenantId.value)
        writeUTF("reversal")
        writeUTF(originalId.value)
    }

private fun sha256(writePayload: DataOutputStream.() -> Unit): String {
    val bytes =
        ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output -> output.writePayload() }
            buffer.toByteArray()
        }
    return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

internal fun Transaction.response(): TransactionResponse =
    TransactionResponse(
        id = id.value,
        tenantId = tenantId.value,
        idempotencyKey = idempotencyKey.value,
        description = description,
        effectiveAt = effectiveAt,
        reversesTransactionId = reversesTransactionId?.value,
        entries =
            entries.map {
                TransactionEntryResponse(
                    id = it.id.value,
                    accountId = it.accountId.value,
                    direction = it.direction.name,
                    amountMinor = it.money.minorUnits,
                    currency = it.money.currency.code,
                )
            },
    )

private fun <T> required(result: DomainResult<T>): T =
    when (result) {
        is DomainResult.Success -> result.value
        is DomainResult.Failure -> ledgerFailure(result.error)
    }

private fun notFound(): Nothing =
    throw LedgerApiException(HttpStatus.NOT_FOUND, "resource_not_found", "The requested resource was not found.")
