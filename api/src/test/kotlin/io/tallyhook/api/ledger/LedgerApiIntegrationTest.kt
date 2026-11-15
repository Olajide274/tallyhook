// This test exercises ledger HTTP, PostgreSQL persistence, constraints, and concurrent idempotency.
package io.tallyhook.api.ledger

import com.fasterxml.jackson.databind.ObjectMapper
import io.tallyhook.ledger.AccountId
import io.tallyhook.ledger.DomainResult
import io.tallyhook.ledger.TenantId
import io.tallyhook.ledger.balance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class LedgerApiIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var ledger: LedgerApplicationService

    @Autowired
    private lateinit var accounts: AccountRepository

    @Autowired
    private lateinit var ledgerRepository: LedgerRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `account posting retrieval sql balance and trial balance work`() {
        val tenant = newTenant()
        val asset = createAccount(tenant, "Cash", "ASSET", "USD")
        val revenue = createAccount(tenant, "Revenue", "REVENUE", "USD")
        val request = transaction(asset.id, revenue.id, 875L)

        val accountResult =
            mockMvc
                .perform(get("/v1/accounts/${asset.id}").header("X-Tenant-Id", tenant))
                .andExpect(status().isOk)
                .andReturn()
        assertEquals(
            asset.id,
            objectMapper.readValue(accountResult.response.contentAsString, AccountResponse::class.java).id,
        )

        val posted = post(tenant, "sale-1", request, 201)
        val fetched =
            mockMvc
                .perform(
                    get("/v1/transactions/${posted.id}").header("X-Tenant-Id", tenant),
                ).andExpect(status().isOk)
                .andReturn()
        assertEquals(
            posted.id,
            objectMapper.readValue(fetched.response.contentAsString, TransactionResponse::class.java).id,
        )

        val assetBalance = getBalance(tenant, asset.id)
        val revenueBalance = getBalance(tenant, revenue.id)
        assertEquals(875L, assetBalance.amountMinor)
        assertEquals(875L, revenueBalance.amountMinor)
        assertEquals("DEBIT", assetBalance.normalBalance)
        assertEquals("CREDIT", revenueBalance.normalBalance)

        val totalsResult =
            mockMvc
                .perform(get("/v1/trial-balance").header("X-Tenant-Id", tenant))
                .andExpect(status().isOk)
                .andReturn()
        val totals = objectMapper.readValue(totalsResult.response.contentAsString, TrialBalanceResponse::class.java)
        assertEquals(875L, totals.totals.single().debitsMinor)
        assertEquals(875L, totals.totals.single().creditsMinor)

        val tenantId = required(TenantId.create(tenant))
        val storedTransaction =
            required(
                io.tallyhook.ledger.TransactionId
                    .create(posted.id),
            ).let { ledgerRepository.find(tenantId, it)!! }
        val assetModel = accounts.find(tenantId, required(AccountId.create(asset.id)))!!.account
        val memoryBalance = required(balance(assetModel, storedTransaction.entries))
        assertEquals(memoryBalance.minorUnits, assetBalance.amountMinor)
    }

    @Test
    fun `unbalanced cross currency and cross tenant posting are rejected`() {
        val tenant = newTenant()
        val asset = createAccount(tenant, "Cash", "ASSET", "USD")
        val revenue = createAccount(tenant, "Revenue", "REVENUE", "USD")
        val unbalanced = transaction(asset.id, revenue.id, 5L, credit = 4L)
        assertEquals(422, postRaw(tenant, "unbalanced", unbalanced).response.status)
        assertEquals(201, postRaw(tenant, "unbalanced", transaction(asset.id, revenue.id, 5L)).response.status)

        val mixed =
            transaction(
                asset.id,
                revenue.id,
                5L,
                currencyForFirst = "EUR",
                currencyForSecond = "USD",
            )
        assertEquals(422, postRaw(tenant, "mixed-currency", mixed).response.status)

        val foreignTenant = newTenant()
        val foreignAccount = createAccount(foreignTenant, "Foreign", "ASSET", "USD")
        assertEquals(
            404,
            postRaw(tenant, "foreign-account", transaction(foreignAccount.id, revenue.id, 5L)).response.status,
        )
    }

    @Test
    fun `idempotent replay returns original transaction and different payload conflicts`() {
        val tenant = newTenant()
        val asset = createAccount(tenant, "Cash", "ASSET", "USD")
        val revenue = createAccount(tenant, "Revenue", "REVENUE", "USD")
        val request = transaction(asset.id, revenue.id, 125L)
        val first = post(tenant, "same-key", request, 201)
        val replay = post(tenant, "same-key", request, 200)
        assertEquals(first.id, replay.id)

        assertEquals(
            409,
            postRaw(tenant, "same-key", transaction(asset.id, revenue.id, 126L)).response.status,
        )
    }

    @Test
    fun `concurrent identical posts create exactly one transaction`() {
        val tenant = newTenant()
        val asset = createAccount(tenant, "Cash", "ASSET", "USD")
        val revenue = createAccount(tenant, "Revenue", "REVENUE", "USD")
        val request = transaction(asset.id, revenue.id, 300L)
        val pool = Executors.newFixedThreadPool(20)
        try {
            val results =
                (1..20)
                    .map {
                        pool.submit<PostedTransaction> {
                            ledger.postTransaction(tenant, "parallel-key", request)
                        }
                    }.map { it.get(30, TimeUnit.SECONDS) }

            assertEquals(1, results.count { !it.replayed })
            assertEquals(19, results.count { it.replayed })
            assertEquals(1, results.map { it.transaction.id.value }.distinct().size)
            val count =
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM transactions WHERE tenant_id = ?",
                    Long::class.java,
                    tenant,
                )
            assertEquals(1L, count)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `reversal nets balances to zero and cannot be repeated`() {
        val tenant = newTenant()
        val asset = createAccount(tenant, "Cash", "ASSET", "USD")
        val revenue = createAccount(tenant, "Revenue", "REVENUE", "USD")
        val posted = post(tenant, "reverse-me", transaction(asset.id, revenue.id, 450L), 201)
        val reversalResult =
            mockMvc
                .perform(
                    post("/v1/transactions/${posted.id}/reversals").header("X-Tenant-Id", tenant),
                ).andExpect(status().isCreated)
                .andReturn()
        val reversal =
            objectMapper.readValue(reversalResult.response.contentAsString, TransactionResponse::class.java)
        assertEquals(posted.id, reversal.reversesTransactionId)
        assertEquals(0L, getBalance(tenant, asset.id).amountMinor)
        assertEquals(0L, getBalance(tenant, revenue.id).amountMinor)

        mockMvc
            .perform(
                post("/v1/transactions/${posted.id}/reversals").header("X-Tenant-Id", tenant),
            ).andExpect(status().isConflict)
    }

    @Test
    fun `cross tenant resource reads return not found`() {
        val owner = newTenant()
        val asset = createAccount(owner, "Cash", "ASSET", "USD")
        val stranger = newTenant()

        mockMvc
            .perform(
                get("/v1/accounts/${asset.id}").header("X-Tenant-Id", stranger),
            ).andExpect(status().isNotFound)
    }

    @Test
    fun `invalid tenant header is rejected by ledger core validation`() {
        mockMvc
            .perform(
                get("/v1/trial-balance").header("X-Tenant-Id", " "),
            ).andExpect(status().isUnprocessableEntity)
    }

    @Test
    fun `database constraint rejects unbalanced transaction at commit`() {
        val tenant = newTenant()
        val asset = createAccount(tenant, "Cash", "ASSET", "USD")
        val revenue = createAccount(tenant, "Revenue", "REVENUE", "USD")
        val transactionId = UUID.randomUUID().toString()
        val tx = TransactionTemplate(transactionManager)

        assertThrows(TransactionSystemException::class.java) {
            tx.executeWithoutResult {
                jdbcTemplate.update(
                    "INSERT INTO transactions (id, tenant_id, description, effective_at) VALUES (?, ?, ?, ?)",
                    transactionId,
                    tenant,
                    "direct unbalanced test",
                    java.sql.Timestamp.from(FIXED_INSTANT),
                )
                jdbcTemplate.update(
                    """
                    INSERT INTO entries
                    (id, tenant_id, transaction_id, account_id, direction, amount_minor, currency, position)
                    VALUES (?, ?, ?, ?, 'DEBIT', 7, 'USD', 0)
                    """.trimIndent(),
                    UUID.randomUUID().toString(),
                    tenant,
                    transactionId,
                    asset.id,
                )
                jdbcTemplate.update(
                    """
                    INSERT INTO entries
                    (id, tenant_id, transaction_id, account_id, direction, amount_minor, currency, position)
                    VALUES (?, ?, ?, ?, 'CREDIT', 6, 'USD', 1)
                    """.trimIndent(),
                    UUID.randomUUID().toString(),
                    tenant,
                    transactionId,
                    revenue.id,
                )
            }
        }
    }

    @Test
    fun `database rejects entry updates and deletes`() {
        val tenant = newTenant()
        val asset = createAccount(tenant, "Cash", "ASSET", "USD")
        val revenue = createAccount(tenant, "Revenue", "REVENUE", "USD")
        val posted = post(tenant, "immutable-entry", transaction(asset.id, revenue.id, 25L), 201)

        assertThrows(DataAccessException::class.java) {
            jdbcTemplate.update(
                "UPDATE entries SET amount_minor = 26 WHERE tenant_id = ? AND transaction_id = ?",
                tenant,
                posted.id,
            )
        }
        assertThrows(DataAccessException::class.java) {
            jdbcTemplate.update(
                "DELETE FROM entries WHERE tenant_id = ? AND transaction_id = ?",
                tenant,
                posted.id,
            )
        }
        assertThrows(DataAccessException::class.java) {
            jdbcTemplate.update(
                "UPDATE transactions SET description = 'changed' WHERE tenant_id = ? AND id = ?",
                tenant,
                posted.id,
            )
        }
        assertThrows(DataAccessException::class.java) {
            jdbcTemplate.update(
                "DELETE FROM transactions WHERE tenant_id = ? AND id = ?",
                tenant,
                posted.id,
            )
        }
    }

    @Test
    fun `database composite foreign key rejects cross tenant account reference`() {
        val owner = newTenant()
        val foreignAccount = createAccount(owner, "Cash", "ASSET", "USD")
        val otherTenant = newTenant()
        val transactionId = UUID.randomUUID().toString()

        assertThrows(DataAccessException::class.java) {
            TransactionTemplate(transactionManager).executeWithoutResult {
                jdbcTemplate.update(
                    "INSERT INTO transactions (id, tenant_id, description, effective_at) VALUES (?, ?, ?, ?)",
                    transactionId,
                    otherTenant,
                    "cross tenant direct test",
                    java.sql.Timestamp.from(FIXED_INSTANT),
                )
                jdbcTemplate.update(
                    """
                    INSERT INTO entries
                    (id, tenant_id, transaction_id, account_id, direction, amount_minor, currency, position)
                    VALUES (?, ?, ?, ?, 'DEBIT', 1, 'USD', 0)
                    """.trimIndent(),
                    UUID.randomUUID().toString(),
                    otherTenant,
                    transactionId,
                    foreignAccount.id,
                )
            }
        }
    }

    private fun createAccount(
        tenant: String,
        name: String,
        type: String,
        currency: String,
    ): AccountResponse {
        val result =
            mockMvc
                .perform(
                    post("/v1/accounts")
                        .header("X-Tenant-Id", tenant)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(CreateAccountRequest(name, type, currency))),
                ).andExpect(status().isCreated)
                .andReturn()
        return objectMapper.readValue(result.response.contentAsString, AccountResponse::class.java)
    }

    private fun post(
        tenant: String,
        key: String,
        request: CreateTransactionRequest,
        expectedStatus: Int,
    ): TransactionResponse {
        val result = postRaw(tenant, key, request)
        assertEquals(expectedStatus, result.response.status)
        return objectMapper.readValue(result.response.contentAsString, TransactionResponse::class.java)
    }

    private fun postRaw(
        tenant: String,
        key: String,
        request: CreateTransactionRequest,
    ) = mockMvc
        .perform(
            post("/v1/transactions")
                .header("X-Tenant-Id", tenant)
                .header("Idempotency-Key", key)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)),
        ).andReturn()

    private fun getBalance(
        tenant: String,
        accountId: String,
    ): BalanceResponse {
        val result =
            mockMvc
                .perform(
                    get("/v1/accounts/$accountId/balance").header("X-Tenant-Id", tenant),
                ).andExpect(status().isOk)
                .andReturn()
        return objectMapper.readValue(result.response.contentAsString, BalanceResponse::class.java)
    }

    @Suppress("LongParameterList")
    private fun transaction(
        debitAccount: String,
        creditAccount: String,
        amount: Long,
        credit: Long = amount,
        currencyForFirst: String = "USD",
        currencyForSecond: String = currencyForFirst,
        secondAccountId: String = creditAccount,
    ) = CreateTransactionRequest(
        description = "integration transaction",
        effectiveAt = FIXED_INSTANT,
        entries =
            listOf(
                CreateEntryRequest(debitAccount, "DEBIT", amount, currencyForFirst),
                CreateEntryRequest(secondAccountId, "CREDIT", credit, currencyForSecond),
            ),
    )

    private fun newTenant(): String = "tenant-${UUID.randomUUID()}"

    @TestConfiguration
    class FixedClockConfiguration {
        @Bean
        @Primary
        fun fixedClock(): Clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)
    }

    companion object {
        private val FIXED_INSTANT: Instant = Instant.parse("2026-01-01T00:00:00Z")

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun registerProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("tallyhook.outbox.enabled") { false }
        }
    }
}

private fun <T> required(result: DomainResult<T>): T =
    when (result) {
        is DomainResult.Success -> result.value
        is DomainResult.Failure -> throw AssertionError("Unexpected domain error: ${result.error}")
    }
