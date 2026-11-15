// This test verifies transactional outbox persistence and Kafka delivery with real containers.
package io.tallyhook.api.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import io.tallyhook.api.ledger.CreateAccountRequest
import io.tallyhook.api.ledger.CreateEntryRequest
import io.tallyhook.api.ledger.CreateTransactionRequest
import io.tallyhook.api.ledger.LedgerApiException
import io.tallyhook.api.ledger.LedgerApplicationService
import io.tallyhook.api.ledger.TransactionResponse
import io.tallyhook.api.ledger.response
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.util.Properties
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class OutboxIntegrationTest {
    @Autowired
    private lateinit var ledger: LedgerApplicationService

    @Autowired
    private lateinit var relay: OutboxRelay

    @Autowired
    private lateinit var store: OutboxStore

    @Autowired
    private lateinit var outboxWriter: OutboxWriter

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var producerFactory: DefaultKafkaProducerFactory<String, String>

    @Autowired
    private lateinit var outboxProperties: OutboxProperties

    @Autowired
    private lateinit var outboxMetrics: OutboxMetrics

    @BeforeEach
    fun createTopic() {
        jdbcTemplate.execute("TRUNCATE TABLE outbox_events RESTART IDENTITY")
        AdminClient.create(mapOf(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers)).use { admin ->
            try {
                admin
                    .createTopics(listOf(NewTopic(TOPIC, 3, 1)))
                    .all()
                    .get(10, TimeUnit.SECONDS)
            } catch (exception: Exception) {
                if (!exception.cause.toString().contains("TopicExistsException")) {
                    throw exception
                }
            }
        }
    }

    @Test
    fun `posting commits one event and relay publishes correct key headers and envelope`() {
        val tenant = newTenant()
        val accounts = createAccounts(tenant)
        val posted = post(tenant, accounts.first, accounts.second)
        val postedEvents = countEvents(tenant, "ledger.transaction.posted")
        assertEquals(1L, postedEvents)

        repeat(3) { relay.pollOnce() }
        val records = consumeTenant(tenant, 3)
        val event =
            records.single {
                objectMapper.readTree(it.value())["eventType"].asText() == "ledger.transaction.posted"
            }
        val key = "$tenant:${posted.id}"

        assertEquals(key, event.key())
        assertEquals(posted.id, objectMapper.readTree(event.value())["aggregateId"].asText())
        assertEquals(
            "ledger.transaction.posted",
            event
                .headers()
                .lastHeader("eventType")
                .value()
                .toString(Charsets.UTF_8),
        )
        assertEquals(
            "1",
            event
                .headers()
                .lastHeader("schemaVersion")
                .value()
                .toString(Charsets.UTF_8),
        )
        assertNotNull(event.headers().lastHeader("eventId"))
        val persisted =
            outboxRow(
                tenant,
                event
                    .headers()
                    .lastHeader("eventId")
                    .value()
                    .toString(Charsets.UTF_8),
            )
        assertNotNull(persisted.publishedAt)
        assertEquals(0, persisted.attempts)
    }

    @Test
    fun `idempotent replay creates no second transaction event`() {
        val tenant = newTenant()
        val (assetId, revenueId) = createAccounts(tenant)
        val request = transaction(assetId, revenueId)
        val first = ledger.postTransaction(tenant, "sale-key", request)
        val replay = ledger.postTransaction(tenant, "sale-key", request)

        assertFalse(first.replayed)
        assertTrue(replay.replayed)
        assertEquals(first.transaction.id, replay.transaction.id)
        assertEquals(1L, countEvents(tenant, "ledger.transaction.posted"))
    }

    @Test
    fun `failed validation and rolled back posting leave no event`() {
        val tenant = newTenant()
        val (assetId, revenueId) = createAccounts(tenant)
        val before = countEvents(tenant, "ledger.transaction.posted")
        assertThrows(LedgerApiException::class.java) {
            ledger.postTransaction(
                tenant,
                "invalid",
                transaction(assetId, revenueId, debitMinor = 10, creditMinor = 9),
            )
        }
        assertEquals(before, countEvents(tenant, "ledger.transaction.posted"))

        val rollbackId = UUID.randomUUID()
        assertThrows(IllegalStateException::class.java) {
            TransactionTemplate(transactionManager).executeWithoutResult {
                outboxWriter.append(
                    OutboxEventDraft(
                        LedgerEventEnvelope(
                            eventId = rollbackId,
                            eventType = "test.rollback",
                            schemaVersion = 1,
                            tenantId = tenant,
                            aggregateType = "transaction",
                            aggregateId = "rollback",
                            occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
                            data = mapOf("amountMinor" to 3L),
                        ),
                    ),
                )
                error("force transaction rollback")
            }
        }
        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE id = ?",
                Int::class.java,
                rollbackId,
            ),
        )
    }

    @Test
    fun `reversal appends ledger reversed event`() {
        val tenant = newTenant()
        val (assetId, revenueId) = createAccounts(tenant)
        val posted = post(tenant, assetId, revenueId)
        val reversal = ledger.reverse(tenant, posted.id).transaction
        val row =
            jdbcTemplate.queryForMap(
                """
                SELECT aggregate_id, payload::TEXT AS payload
                FROM outbox_events
                WHERE tenant_id = ? AND event_type = 'ledger.transaction.reversed'
                """.trimIndent(),
                tenant,
            )

        assertEquals(posted.id, row["aggregate_id"])
        assertEquals(
            reversal.id.value,
            objectMapper.readTree(row["payload"].toString())["data"]["transactionId"].asText(),
        )
        assertEquals(1L, countEvents(tenant, "ledger.transaction.reversed"))
    }

    @Test
    fun `unavailable Kafka records failure and publishes after broker configuration recovers`() {
        val tenant = newTenant()
        val eventId = appendTestEvent(tenant, "retry-aggregate", "ledger.transaction.posted")
        val unavailableFactory =
            DefaultKafkaProducerFactory<String, String>(
                mapOf(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to "127.0.0.1:1",
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                    ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG to 1_000,
                    ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG to 3_000,
                    ProducerConfig.MAX_BLOCK_MS_CONFIG to 1_000,
                    ProducerConfig.RETRIES_CONFIG to 1,
                    ProducerConfig.ACKS_CONFIG to "all",
                ),
            )
        val unavailableRelay =
            OutboxRelay(
                store,
                KafkaTemplate(unavailableFactory),
                outboxProperties,
                outboxMetrics,
            )

        unavailableRelay.pollOnce()
        val failed = outboxRow(tenant, eventId.toString())
        assertEquals(1, failed.attempts)
        assertNotNull(failed.lastError)
        assertEquals(null, failed.publishedAt)
        assertTrue(store.backlog().count > 0)

        unavailableFactory.destroy()
        Thread.sleep(25)
        relay.pollOnce()

        assertNotNull(outboxRow(tenant, eventId.toString()).publishedAt)
        assertEquals(1, consumeTenant(tenant, 1).size)
    }

    @Test
    fun `broker acknowledgement before database mark can redeliver the same event id`() {
        val tenant = newTenant()
        val eventId = appendTestEvent(tenant, "crash-window", "ledger.transaction.posted")

        relay.pollOnce { error("simulated process failure after broker acknowledgement") }
        assertEquals(null, outboxRow(tenant, eventId.toString()).publishedAt)

        Thread.sleep(25)
        relay.pollOnce()
        val redelivered = consumeTenant(tenant, 2)

        assertEquals(2, redelivered.size)
        assertEquals(
            setOf(eventId.toString()),
            redelivered
                .map {
                    it
                        .headers()
                        .lastHeader("eventId")
                        .value()
                        .toString(Charsets.UTF_8)
                }.toSet(),
        )
        assertNotNull(outboxRow(tenant, eventId.toString()).publishedAt)
    }

    @Test
    fun `two relay instances claim disjoint rows and publish each event once`() {
        val tenant = newTenant()
        val eventIds = (1..8).map { appendTestEvent(tenant, "aggregate-$it", "ledger.account.created") }
        val secondRelay =
            OutboxRelay(
                store,
                org.springframework.kafka.core
                    .KafkaTemplate(producerFactory),
                outboxProperties,
                outboxMetrics,
            )
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(4) {
                val first = executor.submit<Int> { relay.pollOnce() }
                val second = executor.submit<Int> { secondRelay.pollOnce() }
                first.get(20, TimeUnit.SECONDS)
                second.get(20, TimeUnit.SECONDS)
            }
        } finally {
            executor.shutdownNow()
        }

        assertTrue(eventIds.all { outboxRow(tenant, it.toString()).publishedAt != null })
        val records = consumeTenant(tenant, eventIds.size)
        val publishedIds =
            records.map {
                it
                    .headers()
                    .lastHeader("eventId")
                    .value()
                    .toString(Charsets.UTF_8)
            }
        assertEquals(eventIds.map(UUID::toString).toSet(), publishedIds.toSet())
        assertEquals(eventIds.size, publishedIds.distinct().size)
    }

    @Test
    fun `later aggregate events wait until earlier event is published`() {
        val tenant = newTenant()
        val first = appendTestEvent(tenant, "one-aggregate", "ledger.transaction.posted")
        val second = appendTestEvent(tenant, "one-aggregate", "ledger.transaction.reversed")

        assertEquals(1, relay.pollOnce())
        assertNotNull(outboxRow(tenant, first.toString()).publishedAt)
        assertEquals(null, outboxRow(tenant, second.toString()).publishedAt)
        assertEquals(1, relay.pollOnce())

        val records = consumeTenant(tenant, 2)
        assertEquals(
            first.toString(),
            records[0]
                .headers()
                .lastHeader("eventId")
                .value()
                .toString(Charsets.UTF_8),
        )
        assertEquals(
            second.toString(),
            records[1]
                .headers()
                .lastHeader("eventId")
                .value()
                .toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `maximum attempts leaves event queryable and operator can requeue it`() {
        val tenant = newTenant()
        val eventId = appendTestEvent(tenant, "dead-letter", "ledger.transaction.posted")
        jdbcTemplate.update(
            "UPDATE outbox_events SET attempts = ? WHERE tenant_id = ? AND id = ?",
            outboxProperties.maxAttempts,
            tenant,
            eventId,
        )

        assertEquals(0, relay.pollOnce())
        assertEquals(1L, store.eventsPastMaxAttempts(outboxProperties.maxAttempts))
        assertTrue(store.requeue(tenant, eventId))
        assertEquals(0, outboxRow(tenant, eventId.toString()).attempts)
        assertEquals(1, relay.pollOnce())
        assertNotNull(outboxRow(tenant, eventId.toString()).publishedAt)
    }

    @Test
    fun `database blocks delete and payload mutation`() {
        val tenant = newTenant()
        val eventId = appendTestEvent(tenant, "immutable-event", "ledger.transaction.posted")

        assertThrows(Exception::class.java) {
            jdbcTemplate.update("DELETE FROM outbox_events WHERE tenant_id = ? AND id = ?", tenant, eventId)
        }
        assertThrows(Exception::class.java) {
            jdbcTemplate.update(
                "UPDATE outbox_events SET payload = '{}'::JSONB WHERE tenant_id = ? AND id = ?",
                tenant,
                eventId,
            )
        }
        assertNotNull(outboxRow(tenant, eventId.toString()))
    }

    private fun createAccounts(tenant: String): Pair<String, String> {
        val asset = ledger.createAccount(tenant, CreateAccountRequest("Cash", "ASSET", "USD"))
        val revenue = ledger.createAccount(tenant, CreateAccountRequest("Revenue", "REVENUE", "USD"))
        return asset.id to revenue.id
    }

    private fun post(
        tenant: String,
        assetId: String,
        revenueId: String,
    ): TransactionResponse =
        ledger
            .postTransaction(tenant, "post-${UUID.randomUUID()}", transaction(assetId, revenueId))
            .transaction
            .response()

    private fun transaction(
        assetId: String,
        revenueId: String,
        debitMinor: Long = 100,
        creditMinor: Long = debitMinor,
    ) = CreateTransactionRequest(
        description = "Outbox integration posting",
        effectiveAt = Instant.parse("2026-01-01T00:00:00Z"),
        entries =
            listOf(
                CreateEntryRequest(assetId, "DEBIT", debitMinor, "USD"),
                CreateEntryRequest(revenueId, "CREDIT", creditMinor, "USD"),
            ),
    )

    private fun appendTestEvent(
        tenant: String,
        aggregateId: String,
        eventType: String,
    ): UUID {
        val eventId = UUID.randomUUID()
        outboxWriter.append(
            OutboxEventDraft(
                LedgerEventEnvelope(
                    eventId = eventId,
                    eventType = eventType,
                    schemaVersion = 1,
                    tenantId = tenant,
                    aggregateType = "test",
                    aggregateId = aggregateId,
                    occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
                    data = mapOf("amountMinor" to 42L, "currency" to "USD"),
                ),
            ),
        )
        return eventId
    }

    private fun countEvents(
        tenant: String,
        eventType: String,
    ): Long =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM outbox_events WHERE tenant_id = ? AND event_type = ?",
            Long::class.java,
            tenant,
            eventType,
        ) ?: 0L

    private fun outboxRow(
        tenant: String,
        eventId: String,
    ): OutboxRow =
        store.find(tenant, UUID.fromString(eventId))
            ?: error("Expected outbox event $eventId to be persisted.")

    private fun consumeTenant(
        tenantId: String,
        expectedCount: Int,
    ): List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> {
        val consumerProperties =
            Properties().apply {
                put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
                put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-test-${UUID.randomUUID()}")
                put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            }
        return KafkaConsumer<String, String>(consumerProperties).use { consumer ->
            consumer.subscribe(listOf(TOPIC))
            val records = mutableListOf<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>>()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (records.size < expectedCount && System.nanoTime() < deadline) {
                records.addAll(
                    consumer
                        .poll(java.time.Duration.ofMillis(200))
                        .filter { objectMapper.readTree(it.value())["tenantId"].asText() == tenantId },
                )
            }
            assertEquals(expectedCount, records.size, "Timed out waiting for $expectedCount events for $tenantId")
            records
        }
    }

    private fun newTenant(): String = "outbox-${UUID.randomUUID()}"

    companion object {
        private const val TOPIC = "tallyhook.ledger.events"

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")

        @Container
        @JvmStatic
        val kafka = KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"))

        @DynamicPropertySource
        @JvmStatic
        fun registerProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers)
            registry.add("tallyhook.outbox.enabled") { true }
            registry.add("tallyhook.outbox.initial-delay") { "3600000" }
            registry.add("tallyhook.outbox.poll-interval") { "3600000" }
            registry.add("tallyhook.outbox.batch-size") { 1 }
            registry.add("tallyhook.outbox.send-timeout") { "2s" }
            registry.add("tallyhook.outbox.base-backoff") { "0ms" }
            registry.add("tallyhook.outbox.max-backoff") { "0ms" }
        }
    }
}
