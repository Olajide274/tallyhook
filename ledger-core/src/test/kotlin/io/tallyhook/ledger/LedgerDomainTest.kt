// This test covers ledger construction invariants, validation errors, and pure calculations.
package io.tallyhook.ledger

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class LedgerDomainTest {
    private val tenant = success(TenantId.create("tenant-a"))
    private val otherTenant = success(TenantId.create("tenant-b"))
    private val usd = success(Currency.create("USD"))
    private val eur = success(Currency.create("EUR"))
    private val asset = account("asset", AccountType.ASSET, usd)
    private val revenue = account("revenue", AccountType.REVENUE, usd)
    private val foreignAsset = Account.create(accountId("foreign-asset"), otherTenant, AccountType.ASSET, usd)
    private val effectiveAt = Instant.parse("2025-01-01T00:00:00Z")

    @Test
    fun `identifiers reject blank and overlong values but accept length boundaries`() {
        assertEquals(
            LedgerError.InvalidIdentifier("tenantId", "must not be blank"),
            failure(TenantId.create("  ")),
        )
        assertTrue(TenantId.create("t".repeat(128)) is DomainResult.Success)
        assertEquals(
            LedgerError.InvalidIdentifier("tenantId", "must not exceed 128 characters"),
            failure(TenantId.create("t".repeat(129))),
        )
        assertTrue(IdempotencyKey.create("k".repeat(256)) is DomainResult.Success)
        assertTrue(IdempotencyKey.create("k".repeat(257)) is DomainResult.Failure)
    }

    @Test
    fun `currency validates uppercase ISO 4217 codes`() {
        assertEquals("USD", usd.code)
        assertEquals(LedgerError.InvalidCurrencyCode("usd"), failure(Currency.create("usd")))
        assertEquals(LedgerError.InvalidCurrencyCode("ZZZ"), failure(Currency.create("ZZZ")))
    }

    @Test
    fun `money arithmetic checks currency and Long boundaries`() {
        assertEquals(
            Money.of(15, usd),
            success(Money.of(10, usd).plus(Money.of(5, usd))),
        )
        assertEquals(
            LedgerError.CurrencyMismatch(usd, eur),
            failure(Money.of(1, usd).plus(Money.of(1, eur))),
        )
        assertEquals(
            LedgerError.ArithmeticOverflow("addition", usd),
            failure(Money.of(Long.MAX_VALUE, usd).plus(Money.of(1, usd))),
        )
        assertEquals(
            LedgerError.ArithmeticOverflow("negation", usd),
            failure(Money.of(Long.MIN_VALUE, usd).negate()),
        )
    }

    @Test
    fun `entries reject zero and negative amounts`() {
        assertEquals(LedgerError.InvalidAmount(0), failure(entryResult("zero", asset, Direction.DEBIT, 0)))
        assertEquals(LedgerError.InvalidAmount(-1), failure(entryResult("negative", asset, Direction.DEBIT, -1)))
    }

    @Test
    fun `transaction rejects a single entry`() {
        assertEquals(
            LedgerError.TooFewEntries(1),
            failure(transactionResult(listOf(entry("only", asset, Direction.DEBIT, 1)), listOf(asset))),
        )
    }

    @Test
    fun `transaction balances each currency independently`() {
        val euroExpense = account("euro-expense", AccountType.EXPENSE, eur)
        val euroPayable = account("euro-payable", AccountType.LIABILITY, eur)
        val transaction =
            success(
                transactionResult(
                    listOf(
                        entry("usd-debit", asset, Direction.DEBIT, 100, usd),
                        entry("usd-credit", revenue, Direction.CREDIT, 100, usd),
                        entry("eur-debit", euroExpense, Direction.DEBIT, 55, eur),
                        entry("eur-credit", euroPayable, Direction.CREDIT, 55, eur),
                    ),
                    listOf(asset, revenue, euroExpense, euroPayable),
                ),
            )
        assertEquals(4, transaction.entries.size)

        assertEquals(
            LedgerError.UnbalancedTransaction(usd, 100, 99),
            failure(
                transactionResult(
                    listOf(
                        entry("unbalanced-debit", asset, Direction.DEBIT, 100),
                        entry("unbalanced-credit", revenue, Direction.CREDIT, 99),
                    ),
                    listOf(asset, revenue),
                ),
            ),
        )
    }

    @Test
    fun `transaction rejects accounts from another tenant and unknown accounts`() {
        assertEquals(
            LedgerError.TenantMismatch(tenant, otherTenant),
            failure(
                transactionResult(
                    listOf(
                        entry("foreign-debit", foreignAsset, Direction.DEBIT, 10),
                        entry("foreign-credit", revenue, Direction.CREDIT, 10),
                    ),
                    listOf(foreignAsset, revenue),
                ),
            ),
        )
        assertEquals(
            LedgerError.AccountNotFound(accountId("missing")),
            failure(
                transactionResult(
                    listOf(
                        entry("missing-debit", accountId("missing"), Direction.DEBIT, 10),
                        entry("known-credit", revenue, Direction.CREDIT, 10),
                    ),
                    listOf(revenue),
                ),
            ),
        )
    }

    @Test
    fun `transaction rejects currency mismatches and invalid descriptions`() {
        assertEquals(
            LedgerError.CurrencyMismatch(usd, eur),
            failure(
                transactionResult(
                    listOf(
                        entry("wrong-currency-debit", asset, Direction.DEBIT, 10, eur),
                        entry("matching-credit", revenue, Direction.CREDIT, 10),
                    ),
                    listOf(asset, revenue),
                ),
            ),
        )
        assertTrue(transactionResult(emptyList(), emptyList(), description = " ") is DomainResult.Failure)
    }

    @Test
    fun `transaction rejects duplicate accounts and entry identifiers`() {
        assertEquals(
            LedgerError.DuplicateAccount(asset.id),
            failure(
                transactionResult(
                    listOf(
                        entry("debit", asset, Direction.DEBIT, 10),
                        entry("credit", revenue, Direction.CREDIT, 10),
                    ),
                    listOf(asset, asset, revenue),
                ),
            ),
        )
        assertEquals(
            LedgerError.DuplicateEntryId(entryId("same")),
            failure(
                transactionResult(
                    listOf(
                        entry("same", asset, Direction.DEBIT, 10),
                        entry("same", revenue, Direction.CREDIT, 10),
                    ),
                    listOf(asset, revenue),
                ),
            ),
        )
    }

    @Test
    fun `transaction total overflow is reported`() {
        val secondAsset = account("second-asset", AccountType.ASSET, usd)
        val secondRevenue = account("second-revenue", AccountType.REVENUE, usd)
        assertEquals(
            LedgerError.ArithmeticOverflow("debit total", usd),
            failure(
                transactionResult(
                    listOf(
                        entry("debit-max", asset, Direction.DEBIT, Long.MAX_VALUE),
                        entry("debit-overflow", secondAsset, Direction.DEBIT, 1),
                        entry("credit-max", revenue, Direction.CREDIT, Long.MAX_VALUE),
                        entry("credit-overflow", secondRevenue, Direction.CREDIT, 1),
                    ),
                    listOf(asset, revenue, secondAsset, secondRevenue),
                ),
            ),
        )
    }

    @Test
    fun `reversal is immutable balanced and cannot repeat`() {
        val original =
            success(
                transactionResult(
                    listOf(
                        entry("original-debit", asset, Direction.DEBIT, 42),
                        entry("original-credit", revenue, Direction.CREDIT, 42),
                    ),
                    listOf(asset, revenue),
                ),
            )
        val reversal =
            success(
                reverseTransaction(
                    original,
                    transactionId("reversal"),
                    effectiveAt.plusSeconds(60),
                    listOf(asset, revenue),
                    emptySet(),
                ),
            )
        assertEquals(original.id, reversal.reversesTransactionId)
        assertEquals("reversal:${original.id.value}", reversal.idempotencyKey.value)
        assertEquals(original.entries.map { it.direction.opposite() }, reversal.entries.map { it.direction })
        assertEquals(original.entries.map { it.money }, reversal.entries.map { it.money })
        assertEquals(Money.zero(usd), success(balance(asset, original.entries + reversal.entries)))
        assertEquals(Money.zero(usd), success(balance(revenue, original.entries + reversal.entries)))
        assertEquals(
            LedgerError.DuplicateReversal(original.id),
            failure(
                reverseTransaction(
                    original,
                    transactionId("reversal-again"),
                    effectiveAt,
                    listOf(asset, revenue),
                    setOf(original.id),
                ),
            ),
        )
        assertEquals(
            LedgerError.InvalidReversalId(original.id),
            failure(reverseTransaction(original, original.id, effectiveAt, listOf(asset, revenue), emptySet())),
        )
    }

    @Test
    fun `balance is signed relative to normal side and detects overflow`() {
        val debit = entry("debit", asset, Direction.DEBIT, 100)
        val credit = entry("credit", asset, Direction.CREDIT, 30)
        assertEquals(Money.of(70, usd), success(balance(asset, listOf(debit, credit))))
        val liability = account("liability", AccountType.LIABILITY, usd)
        assertEquals(
            Money.of(-30, usd),
            success(balance(liability, listOf(entry("credit", liability, Direction.DEBIT, 30)))),
        )
        assertEquals(
            LedgerError.ArithmeticOverflow("addition", usd),
            failure(
                balance(
                    asset,
                    listOf(
                        entry("max", asset, Direction.DEBIT, Long.MAX_VALUE),
                        entry("overflow", asset, Direction.DEBIT, 1),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `trial balance validates tenant accounts currencies and overflow`() {
        val debit = entry("debit", asset, Direction.DEBIT, 10)
        val credit = entry("credit", revenue, Direction.CREDIT, 10)
        assertTrue(trialBalance(tenant, listOf(asset, revenue), listOf(debit, credit)) is DomainResult.Success)
        assertEquals(
            LedgerError.TenantMismatch(tenant, otherTenant),
            failure(trialBalance(tenant, listOf(foreignAsset), emptyList())),
        )
        assertEquals(
            LedgerError.UnbalancedTransaction(usd, 10, 9),
            failure(
                trialBalance(
                    tenant,
                    listOf(asset, revenue),
                    listOf(debit, entry("short-credit", revenue, Direction.CREDIT, 9)),
                ),
            ),
        )
        assertEquals(
            LedgerError.ArithmeticOverflow("debit total", usd),
            failure(
                trialBalance(
                    tenant,
                    listOf(asset, revenue),
                    listOf(
                        entry("max", asset, Direction.DEBIT, Long.MAX_VALUE),
                        entry("overflow", asset, Direction.DEBIT, 1),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `idempotency port identifies unseen keys and previous results`() {
        val port = FakeIdempotencyPort()
        val key = IdempotencyKey.create("request-1").value()
        val transactionId = transactionId("transaction-1")

        assertEquals(IdempotencyLookup.NotSeen, port.lookup(tenant, key))
        port.remember(tenant, key, transactionId)
        assertEquals(IdempotencyLookup.Seen(transactionId), port.lookup(tenant, key))
        assertEquals(IdempotencyLookup.NotSeen, port.lookup(otherTenant, key))
    }

    private fun account(
        name: String,
        type: AccountType,
        currency: Currency,
    ): Account = Account.create(accountId(name), tenant, type, currency)

    private fun entry(
        name: String,
        account: Account,
        direction: Direction,
        amount: Long,
        currency: Currency = account.currency,
    ): Entry = success(entryResult(name, account, direction, amount, currency))

    private fun entry(
        name: String,
        accountId: AccountId,
        direction: Direction,
        amount: Long,
    ): Entry =
        success(
            Entry.create(
                entryId(name),
                accountId,
                direction,
                Money.of(amount, usd),
            ),
        )

    private fun entryResult(
        name: String,
        account: Account,
        direction: Direction,
        amount: Long,
        currency: Currency = account.currency,
    ): DomainResult<Entry> = Entry.create(entryId(name), account.id, direction, Money.of(amount, currency))

    private fun transactionResult(
        entries: Iterable<Entry>,
        accounts: Iterable<Account>,
        description: String = "test transaction",
    ): DomainResult<Transaction> =
        Transaction.create(
            transactionId("transaction"),
            tenant,
            success(IdempotencyKey.create("key")),
            description,
            effectiveAt,
            entries,
            accounts,
        )

    private fun accountId(value: String): AccountId = success(AccountId.create(value))

    private fun transactionId(value: String): TransactionId = success(TransactionId.create(value))

    private fun entryId(value: String): EntryId = success(EntryId.create(value))

    private fun <T> success(result: DomainResult<T>): T =
        when (result) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> error("Expected success, got ${result.error}")
        }

    private fun <T> failure(result: DomainResult<T>): LedgerError =
        when (result) {
            is DomainResult.Success -> error("Expected failure, got ${result.value}")
            is DomainResult.Failure -> result.error
        }

    private fun <T> DomainResult<T>.value(): T = success(this)
}
