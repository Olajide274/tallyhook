<!-- This document describes the transactional outbox and Kafka relay. -->
# Transactional outbox

## Why an outbox

Writing a ledger row to PostgreSQL and publishing to Kafka are two independent writes. Publishing after the database commit can lose an event if the process exits between those actions; publishing before the commit can announce a transaction that later rolls back. Tallyhook writes the ledger change and its event row in one PostgreSQL transaction. The relay publishes committed rows asynchronously.

The API emits `ledger.account.created`, `ledger.transaction.posted`, and `ledger.transaction.reversed`. Event payloads are constructed from the persisted ledger-core objects and serialized outside `ledger-core`.

## Envelope

Every event is versioned JSON. Amounts are integer minor units with an ISO currency code:

```json
{
  "eventId": "b62bb0b8-06d7-4926-b947-120a4a506a63",
  "eventType": "ledger.transaction.posted",
  "schemaVersion": 1,
  "tenantId": "demo-tenant",
  "aggregateType": "transaction",
  "aggregateId": "transaction-id",
  "occurredAt": "2026-01-01T00:00:00Z",
  "data": {
    "transactionId": "transaction-id",
    "description": "Initial sale",
    "entries": [
      {
        "entryId": "entry-id",
        "accountId": "account-id",
        "direction": "DEBIT",
        "amountMinor": 1250,
        "currency": "USD"
      }
    ]
  }
}
```

Kafka topic: `tallyhook.ledger.events`. The message key is `tenantId:aggregateId`; headers are `eventId`, `eventType`, and `schemaVersion`.

## Delivery and ordering

Delivery is **at least once**, not exactly once. A process can receive a broker acknowledgement and fail before storing `published_at`; after the claim lease expires, the same event can be sent again. Consumers must deduplicate using `eventId`.

Per-aggregate order is preserved: the relay only claims the lowest-sequence unpublished event for a tenant and aggregate. A failed earlier event blocks later events for that aggregate, while other aggregates continue. There is no global ordering across aggregates or Kafka partitions.

## Failures and retries

The relay polls a configurable batch in short transactions with `FOR UPDATE SKIP LOCKED` claims and a lease. Producer settings require `acks=all`, idempotence, bounded retries, and bounded timeouts (`max.block.ms=5000`, `request.timeout.ms=5000`, `delivery.timeout.ms=15000`); the relay waits at most 10 seconds for a broker acknowledgement. A row is marked published only after the send future acknowledges the broker. Failed sends increment `attempts`, store a bounded `last_error`, and schedule exponential backoff with jitter and a cap.

After `TALLYHOOK_OUTBOX_MAX_ATTEMPTS` (default 10), a row remains unpublished but is no longer claimed automatically. It is never deleted. Inspect stuck rows:

```sql
SELECT sequence_id, id, tenant_id, aggregate_type, aggregate_id,
       attempts, last_error, next_attempt_at
FROM outbox_events
WHERE published_at IS NULL
  AND attempts >= 10
ORDER BY sequence_id;
```

After resolving the cause, an operator can re-queue a specific event. This resets its attempt counter and makes it immediately eligible; it does not alter the event payload:

```sql
UPDATE outbox_events
SET attempts = 0,
    last_error = NULL,
    next_attempt_at = now(),
    claim_token = NULL,
    claimed_until = NULL
WHERE tenant_id = 'demo-tenant'
  AND id = 'b62bb0b8-06d7-4926-b947-120a4a506a63'
  AND published_at IS NULL;
```

Outbox row contents are immutable and rows cannot be deleted. Only relay state (`published_at`, `attempts`, `last_error`, `next_attempt_at`, and lease fields) can be updated.

## Configuration and metrics

Settings are under `tallyhook.outbox` and can be overridden with environment variables:

| Variable | Default | Meaning |
| --- | --- | --- |
| `TALLYHOOK_OUTBOX_ENABLED` | `true` | Enable scheduled relay polling |
| `TALLYHOOK_OUTBOX_TOPIC` | `tallyhook.ledger.events` | Kafka destination |
| `TALLYHOOK_OUTBOX_POLL_INTERVAL` | `1000ms` | Delay between polls |
| `TALLYHOOK_OUTBOX_BATCH_SIZE` | `50` | Maximum claimed batch |
| `TALLYHOOK_OUTBOX_SEND_TIMEOUT` | `10s` | Broker acknowledgement wait |
| `TALLYHOOK_OUTBOX_MAX_ATTEMPTS` | `10` | Automatic retry limit |
| `TALLYHOOK_OUTBOX_BASE_BACKOFF` | `1s` | Initial retry delay |
| `TALLYHOOK_OUTBOX_MAX_BACKOFF` | `5m` | Retry delay cap |
| `TALLYHOOK_OUTBOX_BACKOFF_JITTER` | `0.2` | Relative jitter amount |
| `TALLYHOOK_OUTBOX_CLAIM_LEASE` | `1m` | Recovery delay after a relay exits mid-claim |

Micrometer metrics are exposed at `/actuator/prometheus`: counters for published events, publish failures, and events reaching the retry limit; gauges for unpublished count, oldest unpublished age in seconds, and current events at the retry limit. The provisioned Grafana dashboard includes backlog, age, and publish-rate panels.
