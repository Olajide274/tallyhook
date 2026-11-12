# This file provides short commands for common development tasks.
.DEFAULT_GOAL := help

.PHONY: help build test check format-check compose-config infra-up infra-down run up down health lint clean

help:
	@printf '%s\n' \
		'build          Build all modules and run tests' \
		'test           Run all tests' \
		'check          Run static analysis and formatting checks' \
		'format-check   Check Kotlin, Gradle, Markdown, and YAML formatting' \
		'compose-config Validate the local Docker Compose configuration' \
		'infra-up       Start local dependencies' \
		'infra-down     Stop local dependencies' \
		'run            Start the API' \
		'up             Build and start the API and local dependencies' \
		'down           Stop the API and local dependencies' \
		'health         Check API connections to local dependencies' \
		'lint           Run static analysis and formatting checks' \
		'clean          Remove generated Gradle build output'

build:
	./gradlew build

test:
	./gradlew test

check:
	./gradlew ktlintCheck detekt spotlessCheck

format-check:
	./gradlew ktlintCheck spotlessCheck

compose-config:
	docker compose config --quiet

infra-up:
	docker compose up -d

infra-down:
	docker compose down

run:
	./gradlew :api:bootRun

up:
	docker compose --profile app up -d --build --wait --wait-timeout 180 postgres kafka rabbitmq prometheus grafana api
	docker compose run --rm kafka-init
	@for attempt in $$(seq 1 90); do \
		if curl --fail --silent http://localhost:8080/health/connections >/dev/null; then exit 0; fi; \
		sleep 2; \
	done; \
	printf '%s\n' 'API did not become healthy within 180 seconds' >&2; exit 1

down:
	docker compose --profile app down

health:
	curl --fail --silent --show-error http://localhost:8080/health/connections

lint:
	./gradlew ktlintCheck detekt spotlessCheck

clean:
	./gradlew clean
