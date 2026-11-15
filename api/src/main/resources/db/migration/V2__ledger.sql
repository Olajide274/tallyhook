-- This migration creates the tenant-scoped immutable ledger schema and database safeguards.
CREATE TABLE accounts (
    id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    type TEXT NOT NULL CHECK (type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    currency CHAR(3) NOT NULL,
    name TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id)
);

CREATE TABLE transactions (
    id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    description TEXT NOT NULL,
    effective_at TIMESTAMPTZ NOT NULL,
    reverses_transaction_id TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, reverses_transaction_id) REFERENCES transactions (tenant_id, id)
);

CREATE UNIQUE INDEX transactions_single_reversal
    ON transactions (tenant_id, reverses_transaction_id)
    WHERE reverses_transaction_id IS NOT NULL;

CREATE INDEX transactions_by_tenant_effective_at
    ON transactions (tenant_id, effective_at);

CREATE TABLE entries (
    id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    transaction_id TEXT NOT NULL,
    account_id TEXT NOT NULL,
    direction TEXT NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    currency CHAR(3) NOT NULL,
    position INTEGER NOT NULL CHECK (position >= 0),
    PRIMARY KEY (tenant_id, transaction_id, position),
    UNIQUE (tenant_id, transaction_id, id),
    FOREIGN KEY (tenant_id, transaction_id) REFERENCES transactions (tenant_id, id),
    FOREIGN KEY (tenant_id, account_id) REFERENCES accounts (tenant_id, id)
);

CREATE INDEX entries_by_tenant_account
    ON entries (tenant_id, account_id);

CREATE TABLE idempotency_keys (
    tenant_id TEXT NOT NULL,
    key TEXT NOT NULL,
    request_hash CHAR(64) NOT NULL,
    transaction_id TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, key),
    UNIQUE (tenant_id, transaction_id),
    CONSTRAINT idempotency_transaction_fk
        FOREIGN KEY (tenant_id, transaction_id)
        REFERENCES transactions (tenant_id, id)
        DEFERRABLE INITIALLY DEFERRED
);

CREATE FUNCTION reject_ledger_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'ledger rows are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER transactions_immutable
    BEFORE UPDATE OR DELETE ON transactions
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

CREATE TRIGGER entries_immutable
    BEFORE UPDATE OR DELETE ON entries
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

CREATE FUNCTION enforce_transaction_balance() RETURNS TRIGGER AS $$
DECLARE
    current_tenant TEXT;
    current_transaction TEXT;
    entry_count BIGINT;
BEGIN
    current_tenant := to_jsonb(NEW)->>'tenant_id';
    current_transaction := COALESCE(to_jsonb(NEW)->>'transaction_id', to_jsonb(NEW)->>'id');

    SELECT COUNT(*)
    INTO entry_count
    FROM entries
    WHERE tenant_id = current_tenant
      AND transaction_id = current_transaction;

    IF entry_count < 2 THEN
        RAISE EXCEPTION 'transaction must contain at least two entries';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM entries
        WHERE tenant_id = current_tenant
          AND transaction_id = current_transaction
        GROUP BY currency
        HAVING SUM(amount_minor) FILTER (WHERE direction = 'DEBIT')
            IS DISTINCT FROM SUM(amount_minor) FILTER (WHERE direction = 'CREDIT')
    ) THEN
        RAISE EXCEPTION 'transaction is unbalanced by currency';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER transactions_balanced_at_commit
    AFTER INSERT ON transactions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION enforce_transaction_balance();

CREATE CONSTRAINT TRIGGER entries_balanced_at_commit
    AFTER INSERT ON entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION enforce_transaction_balance();
