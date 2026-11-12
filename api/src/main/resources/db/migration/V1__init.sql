-- This migration provides a schema smoke test; real ledger tables come in Phase 2.
CREATE TABLE schema_smoke_test (
    id BIGINT PRIMARY KEY,
    created_at TIMESTAMPTZ DEFAULT now()
);
