<!-- This file introduces Tallyhook and documents the Phase 1 development environment. -->
# Tallyhook

Tallyhook is a multi-tenant double-entry ledger and reliable webhook delivery platform built with Kotlin and Spring Boot.

Phase 1 establishes the build, local infrastructure, and a small HTTP API. The ledger, outbox, webhook, and reconciliation modules are currently scaffolding for later phases.

## Requirements

- JDK 21
- Docker Compose v2 with a running Docker Engine

## Quick start

Optionally copy the local development settings:

```sh
cp .env.example .env
```

Start the infrastructure and API:

```sh
make up
```

Check dependency connections:

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

Kafka topic auto-creation is disabled. The `kafka-init` service creates `tallyhook.smoke-test`.

The API is assigned to the Compose profile `app`; `make up` enables this profile and starts the API with the local dependencies. To run it from Gradle instead, start the dependencies and use `make run`.

Stop the containers with `make down`. This keeps the PostgreSQL data volume; remove it explicitly with `docker compose down -v` only when you want to delete that local data.

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
make test
make lint
docker compose config --quiet
```

The Testcontainers integration test is skipped when Docker is unavailable. See [CONTRIBUTING.md](CONTRIBUTING.md) for the development workflow.
