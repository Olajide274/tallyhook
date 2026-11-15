<!-- This file describes the local workflow for contributing to Tallyhook. -->
# Contributing

## Development setup

Install JDK 21 and Docker Compose v2. Point `JAVA_HOME` at JDK 21 before running Gradle or Make targets:

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
```

The project also provides an `.sdkmanrc` pin for SDKMAN users. Start the supporting services with:

```sh
make up
```

## Build and verification

Run the full build and the configured static checks before opening a pull request:

```sh
make lint
make test
docker compose config --quiet
```

## Testcontainers in Codespaces

The Codespaces Docker Engine requires API version 1.40 or newer, while the default docker-java client bundled with Testcontainers 1.20.3 requests API 1.32. That mismatch made Docker discovery fail and caused `ConnectionsIntegrationTest` to be skipped. `api/src/test/resources/docker-java.properties` pins the client to API 1.40, which works with the Codespaces engine without a dependency upgrade. The test starts real PostgreSQL, Kafka, and RabbitMQ containers when Docker is available.

Use `make help` to see convenience targets. The API can be started with `make run`; local infrastructure can be stopped with `make down`.

Keep changes focused on the relevant module, add tests for behavior changes, and update directly related documentation. Pull requests should summarize the change and list the verification commands that were run.
