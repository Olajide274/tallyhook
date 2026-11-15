<!-- This document describes the Phase 3 tenant-scoped ledger HTTP API. -->
# Ledger API

The API listens on `http://localhost:8080` and uses the `/v1` prefix. Every endpoint requires `X-Tenant-Id`. Authentication is not implemented in this phase; the service trusts this header, so access must be limited to trusted clients.

## Accounts

Create an account:

```http
POST /v1/accounts
X-Tenant-Id: demo-tenant
Content-Type: application/json

{"name":"Operating cash","type":"ASSET","currency":"USD"}
```

The server returns `201 Created`, a `Location` header, and the generated account ID:

```json
{
  "id": "generated-account-id",
  "tenantId": "demo-tenant",
  "name": "Operating cash",
  "type": "ASSET",
  "currency": "USD"
}
```

`GET /v1/accounts/{id}` returns the same account representation. `GET /v1/accounts/{id}/balance` returns a signed amount relative to the account's normal balance side:

```json
{
  "accountId": "generated-account-id",
  "amountMinor": 1250,
  "currency": "USD",
  "normalBalance": "DEBIT"
}
```

## Transactions

Posting requires an `Idempotency-Key`. Amounts are positive integer minor units and direction is specified separately:

```http
POST /v1/transactions
X-Tenant-Id: demo-tenant
Idempotency-Key: sale-001
Content-Type: application/json

{
  "description": "Initial sale",
  "effectiveAt": "2026-01-01T00:00:00Z",
  "entries": [
    {"accountId":"cash-id","direction":"DEBIT","amountMinor":1250,"currency":"USD"},
    {"accountId":"revenue-id","direction":"CREDIT","amountMinor":1250,"currency":"USD"}
  ]
}
```

The first successful post returns `201 Created`. Repeating the same tenant, key, and canonical request returns `200 OK` with the original transaction. Reusing the key with a different request returns `409 Conflict`. `GET /v1/transactions/{id}` reads a tenant-scoped transaction.

`POST /v1/transactions/{id}/reversals` posts a new transaction with opposite directions and links it to the original. It returns `201 Created`; a second reversal attempt returns `409 Conflict`.

`GET /v1/trial-balance` returns per-currency debit and credit totals:

```json
{
  "totals": [
    {"currency":"USD","debitsMinor":1250,"creditsMinor":1250}
  ]
}
```

## Errors

Errors use RFC 7807 `application/problem+json` and include a stable `code` extension:

```json
{
  "type": "https://tallyhook.dev/problems/unbalanced_transaction",
  "title": "Unprocessable Entity",
  "status": 422,
  "detail": "Transaction debits and credits must balance per currency.",
  "instance": "/v1/transactions",
  "code": "unbalanced_transaction"
}
```

| HTTP status | Cases |
| --- | --- |
| `400 Bad Request` | Missing required header or malformed JSON |
| `404 Not Found` | Resource not found in the supplied tenant, including another tenant's resource |
| `409 Conflict` | Idempotency key reused for another request; duplicate reversal |
| `422 Unprocessable Entity` | Ledger-core validation failure, unbalanced transaction, or database invariant violation |
| `500 Internal Server Error` | Persistence failure; SQL details and stack traces are not returned |

PostgreSQL independently enforces positive entry amounts, tenant-scoped references, transaction balance at commit, and transaction/entry immutability.
