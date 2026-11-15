<!-- This document describes the pure Kotlin ledger-core domain model. -->
# Ledger core

`ledger-core` is a pure Kotlin module with no runtime dependencies on other project modules or infrastructure frameworks. It models tenant-owned accounts and immutable, append-only transactions made of debit and credit entries.

## Invariants

- IDs are non-blank and length-bounded. Currency codes are validated against the ISO 4217 currencies available in the JDK.
- Entries contain strictly positive amounts. Direction is represented separately by `DEBIT` or `CREDIT`.
- Every transaction has at least two unique entries, references existing accounts from its tenant, and uses each account's currency.
- Debits equal credits independently for every currency. All sum and balance arithmetic is checked for `Long` overflow.
- Reversals are new transactions with opposite entry directions and a reference to the original. The caller supplies existing reversal history so duplicate reversals are rejected without hidden state.
- Balances and trial balances are pure calculations over supplied accounts and entries. Idempotency lookup is a port; persistence belongs to a later phase.

Domain validation returns `DomainResult`, which carries typed `LedgerError` values instead of using exceptions for expected invalid input.

## Integer minor units

Money is stored as a signed `Long` count of the currency's minor units (for example, cents), never as floating point or decimal fractions. This avoids binary rounding and makes equality and checked arithmetic exact. Entry validation requires positive amounts; signed amounts are used only for computed balances.
