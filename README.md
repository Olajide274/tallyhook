# Tallyhook

Tallyhook is a multi-tenant double-entry ledger and reliable webhook delivery platform built with Kotlin and Spring Boot.

Phase 1 establishes the build, local infrastructure, and a small HTTP API. The ledger, outbox, webhook, and reconciliation modules are currently scaffolding for later phases.

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

## Troubleshooting

- **A port is already in use:** free the affected local port and try again. The stack uses `5432` (PostgreSQL), `5672` (RabbitMQ AMQP), `9092`/`29092` (Kafka), `9090` (Prometheus), `3000` (Grafana), `8080` (API), and `15672` (RabbitMQ management).
- **Docker is not running:** start Docker Engine and verify `docker info` succeeds before running `make up` or the Testcontainers integration test.
- **Wrong JDK version:** point `JAVA_HOME` to JDK 21 and ensure `java -version` reports Java 21. Newer JDK version strings can prevent Gradle from starting.
- **RabbitMQ fails to start from stale volume state:** `docker compose down -v` recreates the volumes. This deletes local PostgreSQL and other persisted development data.

## Roadmap

1. Phase 2: `ledger-core`
2. Phase 3: persistence and ledger API
3. Phase 4: outbox
4. Phase 5: webhooks
5. Phase 6: reconciliation

See [CONTRIBUTING.md](CONTRIBUTING.md) for the development workflow.
