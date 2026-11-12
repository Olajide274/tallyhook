<!-- This file describes the local workflow for contributing to Tallyhook. -->
# Contributing

## Development setup

Install JDK 21 and Docker Compose v2, then start the supporting services:

```sh
docker compose up -d
```

## Build and verification

Run the full build and the configured static checks before opening a pull request:

```sh
./gradlew build
./gradlew ktlintCheck detekt spotlessCheck
docker compose config --quiet
```

Use `make help` to see equivalent convenience targets. The API can be started with `make run`; local infrastructure can be stopped with `make infra-down`.

Keep changes focused on the relevant module, add tests for behavior changes, and update directly related documentation. Pull requests should summarize the change and list the verification commands that were run.
