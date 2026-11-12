# This file provides short commands for common development tasks.
.DEFAULT_GOAL := help

.PHONY: help build test check format-check compose-config infra-up infra-down run

help:
	@printf '%s\n' \
		'build          Build all modules and run tests' \
		'test           Run all tests' \
		'check          Run static analysis and formatting checks' \
		'format-check   Check Kotlin, Gradle, Markdown, and YAML formatting' \
		'compose-config Validate the local Docker Compose configuration' \
		'infra-up       Start local dependencies' \
		'infra-down     Stop local dependencies' \
		'run            Start the API'

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
