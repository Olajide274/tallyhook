// This test generates ledger data to verify balancing properties across many inputs.
package io.tallyhook.ledger

import io.kotest.core.spec.style.StringSpec
import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.time.Instant

class LedgerPropertyTest :
    StringSpec({
        "generated transactions balance each currency" {
            checkAll(200, Arb.long(1L..1_000_000L), Arb.long(1L..1_000_000L)) { usdAmount, eurAmount ->
                val tenant = tenantId("property-tenant")
                val usd = currency("USD")
                val eur = currency("EUR")
                val accounts =
                    listOf(
                        account("usd-debit-account", tenant, AccountType.ASSET, usd),
                        account("usd-credit-account", tenant, AccountType.REVENUE, usd),
                        account("eur-debit-account", tenant, AccountType.EXPENSE, eur),
                        account("eur-credit-account", tenant, AccountType.LIABILITY, eur),
                    )
                val entries =
                    listOf(
                        entry("usd-debit", accounts[0], Direction.DEBIT, usdAmount, usd),
                        entry("usd-credit", accounts[1], Direction.CREDIT, usdAmount, usd),
                        entry("eur-debit", accounts[2], Direction.DEBIT, eurAmount, eur),
                        entry("eur-credit", accounts[3], Direction.CREDIT, eurAmount, eur),
                    )

                val transaction = success(createTransaction("valid", tenant, entries, accounts))

                for (currency in listOf(usd, eur)) {
                    val currencyEntries = transaction.entries.filter { it.money.currency == currency }
                    assertEquals(
                        currencyEntries.filter { it.direction == Direction.DEBIT }.sumOf { it.money.minorUnits },
                        currencyEntries.filter { it.direction == Direction.CREDIT }.sumOf { it.money.minorUnits },
                    )
                }
            }
        }

        "reversing generated transactions clears every account balance" {
            checkAll(200, Arb.long(1L..Long.MAX_VALUE / 2)) { amount ->
                val tenant = tenantId("reverse-tenant")
                val usd = currency("USD")
                val asset = account("reverse-asset", tenant, AccountType.ASSET, usd)
                val revenue = account("reverse-revenue", tenant, AccountType.REVENUE, usd)
                val accounts = listOf(asset, revenue)
                val original =
                    success(
                        createTransaction(
                            "original",
                            tenant,
                            listOf(
                                entry("original-debit", asset, Direction.DEBIT, amount, usd),
                                entry("original-credit", revenue, Direction.CREDIT, amount, usd),
                            ),
                            accounts,
                        ),
                    )
                val reversal =
                    success(
                        reverseTransaction(
                            original,
                            transactionId("reversal"),
                            Instant.EPOCH,
                            accounts,
                            emptySet(),
                        ),
                    )

                for (account in accounts) {
                    assertEquals(Money.zero(usd), success(balance(account, original.entries + reversal.entries)))
                }
            }
        }

        "balance computation is independent of entry order" {
            val input = Arb.list(Arb.long(1L..1_000_000L), 1..30)
            checkAll(200, input) { generated ->
                val tenant = tenantId("order-tenant")
                val usd = currency("USD")
                val asset = account("order-asset", tenant, AccountType.ASSET, usd)
                val entries =
                    generated.mapIndexed { index, amount ->
                        val direction = if (index % 2 == 0) Direction.DEBIT else Direction.CREDIT
                        entry("order-$index", asset, direction, amount, usd)
                    }

                assertEquals(success(balance(asset, entries)), success(balance(asset, entries.reversed())))
            }
        }

        "trial balance remains balanced after any generated sequence of valid transactions" {
            checkAll(100, Arb.list(Arb.long(1L..1_000_000L), 1..20)) { amounts ->
                val tenant = tenantId("sequence-tenant")
                val usd = currency("USD")
                val asset = account("sequence-asset", tenant, AccountType.ASSET, usd)
                val revenue = account("sequence-revenue", tenant, AccountType.REVENUE, usd)
                val accounts = listOf(asset, revenue)
                val transactions =
                    amounts.mapIndexed { index, amount ->
                        success(
                            createTransaction(
                                "sequence-$index",
                                tenant,
                                listOf(
                                    entry("sequence-debit-$index", asset, Direction.DEBIT, amount, usd),
                                    entry("sequence-credit-$index", revenue, Direction.CREDIT, amount, usd),
                                ),
                                accounts,
                            ),
                        )
                    }

                assertTrue(trialBalance(tenant, accounts, transactions.flatMap { it.entries }) is DomainResult.Success)
            }
        }
    })

private fun account(
    name: String,
    tenant: TenantId,
    type: AccountType,
    currency: Currency,
): Account = Account.create(accountId(name), tenant, type, currency)

private fun entry(
    name: String,
    account: Account,
    direction: Direction,
    amount: Long,
    currency: Currency,
): Entry =
    success(
        Entry.create(
            entryId(name),
            account.id,
            direction,
            Money.of(amount, currency),
        ),
    )

private fun createTransaction(
    name: String,
    tenant: TenantId,
    entries: List<Entry>,
    accounts: List<Account>,
): DomainResult<Transaction> =
    Transaction.create(
        transactionId(name),
        tenant,
        success(IdempotencyKey.create("key-$name")),
        name,
        Instant.EPOCH,
        entries,
        accounts,
    )

private fun currency(code: String): Currency = success(Currency.create(code))

private fun tenantId(value: String): TenantId = success(TenantId.create(value))

private fun accountId(value: String): AccountId = success(AccountId.create(value))

private fun transactionId(value: String): TransactionId = success(TransactionId.create(value))

private fun entryId(value: String): EntryId = success(EntryId.create(value))

private fun <T> success(result: DomainResult<T>): T =
    when (result) {
        is DomainResult.Success -> result.value
        is DomainResult.Failure -> error("Expected success, got ${result.error}")
    }
