# Tallyhook

Tallyhook is a multi-tenant double-entry ledger and reliable webhook delivery platform built with Kotlin and Spring Boot.

The project has a pure Kotlin ledger core, a PostgreSQL-backed ledger HTTP API, and a transactional outbox with a Kafka relay. Webhooks and reconciliation are later phases.

## Requirements

- JDK 21
- Docker Compose v2 with a running Docker Engine
- `JAVA_HOME` must point to JDK 21; Gradle fails to parse the version string on newer JDKs.

## Quick start

Optionally copy the local development settings:

```sh
cp .env.example .env
```

Start the infrastructure and API:

```sh
make up
```

Check dependency connections. `make health` calls `/health/connections`:

```sh
make health
```

The health endpoint returns HTTP 200 and `{"postgres":"UP","kafka":"UP","rabbitmq":"UP"}` when all dependencies respond. It returns HTTP 503 with a short reason for any failed check.

## Local service URLs

These credentials are for local development only. Do not use them in production.

| Service | URL / address | Development credentials |
| --- | --- | --- |
| Tallyhook API | `http://localhost:8080` | — |
| API connection health | `http://localhost:8080/health/connections` | — |
| Prometheus metrics | `http://localhost:8080/actuator/prometheus` | — |
| PostgreSQL | `localhost:5432` | Database `tallyhook`, user `tallyhook`, password `tallyhook` |
| Kafka (host) | `localhost:29092` | No authentication |
| Kafka (inside Compose) | `kafka:9092` | No authentication |
| RabbitMQ AMQP | `localhost:5672` | User `tallyhook`, password `tallyhook` |
| RabbitMQ management UI | `http://localhost:15672` | User `tallyhook`, password `tallyhook` |
| Prometheus UI | `http://localhost:9090` | No authentication |
| Grafana | `http://localhost:3000` | User `admin`, password `admin` |

Kafka topic auto-creation is disabled. The `kafka-init` service creates `tallyhook.smoke-test` and `tallyhook.ledger.events` (3 partitions, replication factor 1 for local development).

The API is assigned to the Compose profile `app`; `make up` enables this profile and starts the API with the local dependencies. To run it from Gradle instead, start the dependencies and use `make run`.

Stop the containers with `make down`. This keeps the PostgreSQL data volume; remove it explicitly with `docker compose down -v` only when you want to delete that local data.

## Ledger API

The API is available under `/v1`. Send `X-Tenant-Id` on every request. Authentication is out of scope for this phase, so the tenant header is trusted for now; do not expose this API to untrusted clients.

| Method | Endpoint | Purpose |
| --- | --- | --- |
| `POST` | `/v1/accounts` | Create an account |
| `GET` | `/v1/accounts/{id}` | Read an account |
| `GET` | `/v1/accounts/{id}/balance` | Read its signed balance in minor units |
| `POST` | `/v1/transactions` | Post an immutable transaction; requires `Idempotency-Key` |
| `GET` | `/v1/transactions/{id}` | Read a transaction |
| `POST` | `/v1/transactions/{id}/reversals` | Post a reversing transaction |
| `GET` | `/v1/trial-balance` | Read debit and credit totals per currency |

Amounts are integer minor units, never decimal amounts. Example workflow:

```sh
export TENANT_ID=demo-tenant

ASSET_ID=$(curl --fail --silent --show-error -X POST http://localhost:8080/v1/accounts \
  -H "X-Tenant-Id: $TENANT_ID" -H 'Content-Type: application/json' \
  -d '{"name":"Operating cash","type":"ASSET","currency":"USD"}' | jq -r .id)

REVENUE_ID=$(curl --fail --silent --show-error -X POST http://localhost:8080/v1/accounts \
  -H "X-Tenant-Id: $TENANT_ID" -H 'Content-Type: application/json' \
  -d '{"name":"Sales revenue","type":"REVENUE","currency":"USD"}' | jq -r .id)

BODY=$(printf '{"description":"Initial sale","effectiveAt":"2026-01-01T00:00:00Z","entries":[{"accountId":"%s","direction":"DEBIT","amountMinor":1250,"currency":"USD"},{"accountId":"%s","direction":"CREDIT","amountMinor":1250,"currency":"USD"}]}' "$ASSET_ID" "$REVENUE_ID")

curl --fail --silent --show-error -X POST http://localhost:8080/v1/transactions \
  -H "X-Tenant-Id: $TENANT_ID" -H 'Idempotency-Key: sale-001' \
  -H 'Content-Type: application/json' -d "$BODY"

# Repeating the same request and key returns the original transaction with HTTP 200.
curl --fail --silent --show-error -X POST http://localhost:8080/v1/transactions \
  -H "X-Tenant-Id: $TENANT_ID" -H 'Idempotency-Key: sale-001' \
  -H 'Content-Type: application/json' -d "$BODY"

curl --fail --silent --show-error \
  -H "X-Tenant-Id: $TENANT_ID" \
  "http://localhost:8080/v1/accounts/$ASSET_ID/balance"

TRANSACTION_ID=$(curl --fail --silent --show-error -X POST http://localhost:8080/v1/transactions \
  -H "X-Tenant-Id: $TENANT_ID" -H 'Idempotency-Key: sale-002' \
  -H 'Content-Type: application/json' \
  -d "$(printf '%s' "$BODY" | sed 's/Initial sale/Second sale/')" | jq -r .id)

curl --fail --silent --show-error -X POST \
  -H "X-Tenant-Id: $TENANT_ID" \
  "http://localhost:8080/v1/transactions/$TRANSACTION_ID/reversals"
```

### Design decisions

- Amounts use signed `Long` integer minor units; entries themselves must be positive and carry direction separately.
- Idempotency is scoped by tenant and key. A stable SHA-256 request hash makes identical retries return the original result; reusing a key for another payload returns `409 Conflict`.
- Cross-tenant resources return `404 Not Found` so their existence is not disclosed.
- Ledger transaction balance, entry-count, immutability, and tenant-scoped foreign-key invariants are enforced by PostgreSQL as well as validated through `ledger-core`.

## Consuming events

Ledger events are published to `tallyhook.ledger.events` with at-least-once delivery. Consumers must deduplicate using the envelope's `eventId`. Ordering is guaranteed only within each tenant-and-aggregate Kafka key; there is no global ordering across aggregates. See [docs/outbox.md](docs/outbox.md).

## Project modules

| Module | Responsibility |
| --- | --- |
| `common` | Shared types and utilities |
| `ledger-core` | Pure Kotlin ledger rules |
| `api` | PostgreSQL persistence and Spring Boot ledger HTTP API |
| `outbox` | Reliable event outbox and relay |
| `webhooks` | Reliable webhook delivery |
| `reconciliation` | Comparing ledger records with external statements |

## Quality checks

```sh
make test
make lint
docker compose config --quiet
```

## Troubleshooting

- **A port is already in use:** free the affected local port and try again. The stack uses `5432` (PostgreSQL), `5672` (RabbitMQ AMQP), `9092`/`29092` (Kafka), `9090` (Prometheus), `3000` (Grafana), `8080` (API), and `15672` (RabbitMQ management).
- **Docker is not running:** start Docker Engine and verify `docker info` succeeds before running `make up` or the Testcontainers integration test.
- **Wrong JDK version:** point `JAVA_HOME` to JDK 21 and ensure `java -version` reports Java 21. Newer JDK version strings can prevent Gradle from starting.
- **RabbitMQ fails to start from stale volume state:** `docker compose down -v` recreates the volumes. This deletes local PostgreSQL and other persisted development data.

## Roadmap

1. Phase 2: `ledger-core` — done
2. Phase 3: persistence and ledger API — done
3. Phase 4: outbox — done
4. Phase 5: webhooks
5. Phase 6: reconciliation

See [CONTRIBUTING.md](CONTRIBUTING.md) for the development workflow.
