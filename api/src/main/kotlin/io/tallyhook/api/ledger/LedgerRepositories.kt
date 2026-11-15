// This file implements tenant-scoped JDBC persistence for ledger-core objects.
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
import io.tallyhook.ledger.IdempotencyPort
import io.tallyhook.ledger.LedgerError
import io.tallyhook.ledger.Money
import io.tallyhook.ledger.TenantId
import io.tallyhook.ledger.Transaction
import io.tallyhook.ledger.TransactionId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

data class AccountRecord(
    val account: Account,
    val name: String,
) {
    fun response(): AccountResponse =
        AccountResponse(
            id = account.id.value,
            tenantId = account.tenantId.value,
            name = name,
            type = account.type.name,
            currency = account.currency.code,
        )
}

@Repository
class AccountRepository(
    private val jdbc: JdbcClient,
) {
    fun insert(
        account: Account,
        name: String,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO accounts (id, tenant_id, type, currency, name)
                VALUES (:id, :tenantId, :type, :currency, :name)
                """.trimIndent(),
            ).param("id", account.id.value)
            .param("tenantId", account.tenantId.value)
            .param("type", account.type.name)
            .param("currency", account.currency.code)
            .param("name", name)
            .update()
    }

    fun find(
        tenantId: TenantId,
        accountId: AccountId,
    ): AccountRecord? =
        jdbc
            .sql(
                """
                SELECT id, tenant_id, type, currency, name
                FROM accounts
                WHERE tenant_id = :tenantId AND id = :accountId
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("accountId", accountId.value)
            .query { resultSet, _ -> mapAccount(resultSet) }
            .list()
            .firstOrNull()

    fun findByIds(
        tenantId: TenantId,
        accountIds: Collection<AccountId>,
    ): List<AccountRecord> {
        if (accountIds.isEmpty()) {
            return emptyList()
        }
        return jdbc
            .sql(
                """
                SELECT id, tenant_id, type, currency, name
                FROM accounts
                WHERE tenant_id = :tenantId AND id IN (:accountIds)
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("accountIds", accountIds.map(AccountId::value))
            .query { resultSet, _ -> mapAccount(resultSet) }
            .list()
    }

    private fun mapAccount(resultSet: ResultSet): AccountRecord {
        val tenantId = required(TenantId.create(resultSet.getString("tenant_id")))
        val accountId = required(AccountId.create(resultSet.getString("id")))
        val type = AccountType.valueOf(resultSet.getString("type"))
        val currency = required(Currency.create(resultSet.getString("currency").trim()))
        return AccountRecord(
            Account.create(accountId, tenantId, type, currency),
            resultSet.getString("name"),
        )
    }
}

data class IdempotencyRecord(
    val requestHash: String,
    val transactionId: TransactionId,
)

@Repository
class PostgresIdempotencyRepository(
    private val jdbc: JdbcClient,
) : IdempotencyPort {
    override fun lookup(
        tenantId: TenantId,
        key: IdempotencyKey,
    ): IdempotencyLookup {
        val record = findRecord(tenantId, key)
        return record?.let { IdempotencyLookup.Seen(it.transactionId) } ?: IdempotencyLookup.NotSeen
    }

    fun findRecord(
        tenantId: TenantId,
        key: IdempotencyKey,
    ): IdempotencyRecord? =
        jdbc
            .sql(
                """
                SELECT request_hash, transaction_id
                FROM idempotency_keys
                WHERE tenant_id = :tenantId AND key = :key
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("key", key.value)
            .query { resultSet, _ ->
                IdempotencyRecord(
                    resultSet.getString("request_hash").trim(),
                    required(TransactionId.create(resultSet.getString("transaction_id"))),
                )
            }.list()
            .firstOrNull()

    fun reserve(
        tenantId: TenantId,
        key: IdempotencyKey,
        requestHash: String,
        transactionId: TransactionId,
    ): Boolean =
        jdbc
            .sql(
                """
                INSERT INTO idempotency_keys (tenant_id, key, request_hash, transaction_id)
                VALUES (:tenantId, :key, :requestHash, :transactionId)
                ON CONFLICT DO NOTHING
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("key", key.value)
            .param("requestHash", requestHash)
            .param("transactionId", transactionId.value)
            .update() == 1
}

data class TrialBalanceTotals(
    val currency: Currency,
    val debitsMinor: Long,
    val creditsMinor: Long,
)

@Repository
class LedgerRepository(
    private val jdbc: JdbcClient,
    private val accounts: AccountRepository,
) {
    fun insert(transaction: Transaction) {
        jdbc
            .sql(
                """
                INSERT INTO transactions (id, tenant_id, description, effective_at, reverses_transaction_id)
                VALUES (:id, :tenantId, :description, :effectiveAt, :reversesTransactionId)
                """.trimIndent(),
            ).param("id", transaction.id.value)
            .param("tenantId", transaction.tenantId.value)
            .param("description", transaction.description)
            .param("effectiveAt", Timestamp.from(transaction.effectiveAt))
            .param("reversesTransactionId", transaction.reversesTransactionId?.value)
            .update()

        transaction.entries.forEachIndexed { index, entry ->
            insertEntry(transaction.tenantId, transaction.id, index, entry)
        }
    }

    fun find(
        tenantId: TenantId,
        transactionId: TransactionId,
    ): Transaction? {
        val header =
            jdbc
                .sql(
                    """
                    SELECT t.id, t.tenant_id, t.description, t.effective_at,
                           t.reverses_transaction_id, i.key
                    FROM transactions t
                    JOIN idempotency_keys i
                      ON i.tenant_id = t.tenant_id AND i.transaction_id = t.id
                    WHERE t.tenant_id = :tenantId AND t.id = :transactionId
                    """.trimIndent(),
                ).param("tenantId", tenantId.value)
                .param("transactionId", transactionId.value)
                .query { rs, _ ->
                    TransactionHeader(
                        id = required(TransactionId.create(rs.getString("id"))),
                        tenantId = required(TenantId.create(rs.getString("tenant_id"))),
                        description = rs.getString("description"),
                        effectiveAt = rs.getTimestamp("effective_at").toInstant(),
                        reversesTransactionId =
                            rs.getString("reverses_transaction_id")?.let {
                                required(TransactionId.create(it))
                            },
                        idempotencyKey = required(IdempotencyKey.create(rs.getString("key"))),
                    )
                }.list()
                .firstOrNull() ?: return null

        val entries = findEntries(tenantId, transactionId)
        val accountIds = entries.map(Entry::accountId).distinct()
        val accountModels = accounts.findByIds(tenantId, accountIds).map(AccountRecord::account)
        return when (
            val result =
                Transaction.create(
                    id = header.id,
                    tenantId = header.tenantId,
                    idempotencyKey = header.idempotencyKey,
                    description = header.description,
                    effectiveAt = header.effectiveAt,
                    entries = entries,
                    accounts = accountModels,
                    reversesTransactionId = header.reversesTransactionId,
                )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> error("Persisted transaction violates ledger-core invariants: ${result.error}")
        }
    }

    fun hasReversal(
        tenantId: TenantId,
        transactionId: TransactionId,
    ): Boolean =
        jdbc
            .sql(
                """
                SELECT EXISTS (
                    SELECT 1 FROM transactions
                    WHERE tenant_id = :tenantId AND reverses_transaction_id = :transactionId
                )
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("transactionId", transactionId.value)
            .query(Boolean::class.java)
            .single()

    fun balanceTotals(
        tenantId: TenantId,
        accountId: AccountId,
        currency: Currency,
    ): Pair<Long, Long> {
        val totals =
            jdbc
                .sql(
                    """
                    SELECT
                        COALESCE(SUM(amount_minor) FILTER (WHERE direction = 'DEBIT'), 0) AS debits,
                        COALESCE(SUM(amount_minor) FILTER (WHERE direction = 'CREDIT'), 0) AS credits
                    FROM entries
                    WHERE tenant_id = :tenantId AND account_id = :accountId
                    """.trimIndent(),
                ).param("tenantId", tenantId.value)
                .param("accountId", accountId.value)
                .query { rs, _ -> rs.getBigDecimal("debits") to rs.getBigDecimal("credits") }
                .single()
        return exactLong(totals.first, "debit balance", currency) to
            exactLong(totals.second, "credit balance", currency)
    }

    fun trialBalance(tenantId: TenantId): List<TrialBalanceTotals> =
        jdbc
            .sql(
                """
                SELECT currency,
                       SUM(amount_minor) FILTER (WHERE direction = 'DEBIT') AS debits,
                       SUM(amount_minor) FILTER (WHERE direction = 'CREDIT') AS credits
                FROM entries
                WHERE tenant_id = :tenantId
                GROUP BY currency
                ORDER BY currency
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .query { rs, _ ->
                val currency = required(Currency.create(rs.getString("currency").trim()))
                TrialBalanceTotals(
                    currency = currency,
                    debitsMinor =
                        exactLong(rs.getBigDecimal("debits") ?: BigDecimal.ZERO, "trial debit total", currency),
                    creditsMinor =
                        exactLong(rs.getBigDecimal("credits") ?: BigDecimal.ZERO, "trial credit total", currency),
                )
            }.list()

    private fun findEntries(
        tenantId: TenantId,
        transactionId: TransactionId,
    ): List<Entry> =
        jdbc
            .sql(
                """
                SELECT id, account_id, direction, amount_minor, currency
                FROM entries
                WHERE tenant_id = :tenantId AND transaction_id = :transactionId
                ORDER BY position
                """.trimIndent(),
            ).param("tenantId", tenantId.value)
            .param("transactionId", transactionId.value)
            .query { rs, _ ->
                val currency = required(Currency.create(rs.getString("currency").trim()))
                required(
                    Entry.create(
                        id = required(EntryId.create(rs.getString("id"))),
                        accountId = required(AccountId.create(rs.getString("account_id"))),
                        direction = Direction.valueOf(rs.getString("direction")),
                        money = Money.of(rs.getLong("amount_minor"), currency),
                    ),
                )
            }.list()

    private fun insertEntry(
        tenantId: TenantId,
        transactionId: TransactionId,
        position: Int,
        entry: Entry,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO entries
                    (id, tenant_id, transaction_id, account_id, direction, amount_minor, currency, position)
                VALUES
                    (:id, :tenantId, :transactionId, :accountId, :direction, :amountMinor, :currency, :position)
                """.trimIndent(),
            ).param("id", entry.id.value)
            .param("tenantId", tenantId.value)
            .param("transactionId", transactionId.value)
            .param("accountId", entry.accountId.value)
            .param("direction", entry.direction.name)
            .param("amountMinor", entry.money.minorUnits)
            .param("currency", entry.money.currency.code)
            .param("position", position)
            .update()
    }

    private fun exactLong(
        value: BigDecimal,
        operation: String,
        currency: Currency,
    ): Long =
        try {
            value.longValueExact()
        } catch (_: ArithmeticException) {
            ledgerFailure(LedgerError.ArithmeticOverflow(operation, currency))
        }
}

private data class TransactionHeader(
    val id: TransactionId,
    val tenantId: TenantId,
    val description: String,
    val effectiveAt: Instant,
    val reversesTransactionId: TransactionId?,
    val idempotencyKey: IdempotencyKey,
)

private fun <T> required(result: DomainResult<T>): T =
    when (result) {
        is DomainResult.Success -> result.value
        is DomainResult.Failure -> error("Stored ledger value is invalid: ${result.error}")
    }
