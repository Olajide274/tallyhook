<!-- This file records the decision to make PostgreSQL authoritative for ledger data. -->
# ADR 0002: Use PostgreSQL as the authoritative ledger store

- Status: Accepted
- Date: 2026-11-12

## Context

Ledger entries require durable storage and transactional consistency. Kafka and RabbitMQ support asynchronous event workflows but are not substitutes for the authoritative ledger.

## Decision

Use PostgreSQL as the source of truth for ledger state. Treat messages sent through Kafka or RabbitMQ as integration and delivery mechanisms, not as the authoritative record.

## Consequences

Ledger writes and database constraints define durable state. Message publication and delivery need retryable designs that do not compromise that state; those mechanisms will be implemented in later phases.
