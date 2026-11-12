<!-- This file records the decision to begin with a Kotlin modular monolith. -->
# ADR 0001: Start with a Kotlin modular monolith

- Status: Accepted
- Date: 2026-11-12

## Context

Tallyhook needs clear boundaries between the API, ledger rules, event delivery, and reconciliation without the deployment and operational costs of multiple services during initial development.

## Decision

Build the first release as a Kotlin/Gradle multi-module application. Keep domain logic in focused modules and run the HTTP API as the initial Spring Boot application.

## Consequences

Module boundaries can be tested and evolved independently while deployment remains simple. Splitting a module into a service later will require an explicit contract and operational plan.
