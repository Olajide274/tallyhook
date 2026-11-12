<!-- This file introduces Tallyhook and explains how to build and run Phase 1. -->
# Tallyhook

Tallyhook is a multi-tenant double-entry ledger and reliable webhook delivery platform built with Kotlin and Spring Boot.

Phase 1 establishes the build, local infrastructure, and a small HTTP API. The ledger, outbox, webhook, and reconciliation modules are currently scaffolding for later phases.

## Requirements

- JDK 21
- Docker Compose v2

## Quick start

Start the local dependencies:

```sh
docker compose up -d
```

Build and test the project:

```sh
./gradlew build
```

Run the API:

```sh
./gradlew :api:bootRun
```

The API listens on `http://localhost:8080`:

- `GET /` returns a hello-world message.
- `GET /health/connections` reports API availability and marks database, Kafka, and RabbitMQ checks as `NOT_CONFIGURED` until their integrations are implemented.

The local services are available on PostgreSQL `5432`, Kafka `9092`, RabbitMQ AMQP `5672`, and the RabbitMQ management UI `http://localhost:15672` (local development credentials: `tallyhook` / `tallyhook`).

Stop the local dependencies with `docker compose down`. Add `-v` only when you also want to remove the local PostgreSQL data volume.

## Project modules

| Module | Responsibility |
| --- | --- |
| `common` | Shared types and utilities |
| `ledger-core` | Pure Kotlin ledger rules |
| `api` | Runnable Spring Boot HTTP application |
| `outbox` | Reliable event outbox and relay |
| `webhooks` | Reliable webhook delivery |
| `reconciliation` | Comparing ledger records with external statements |

## Quality checks

```sh
./gradlew build
./gradlew ktlintCheck detekt spotlessCheck
docker compose config --quiet
```

Convenience commands are also available through `make help`; see [CONTRIBUTING.md](CONTRIBUTING.md) for the development workflow.
