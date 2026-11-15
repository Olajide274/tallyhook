-- This migration adds the durable transactional event outbox and relay claim fields.
CREATE TABLE outbox_events (
    sequence_id BIGSERIAL NOT NULL UNIQUE,
    id UUID PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    aggregate_type TEXT NOT NULL,
    aggregate_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error TEXT,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    claim_token UUID,
    claimed_until TIMESTAMPTZ
);

CREATE INDEX outbox_events_due_unpublished
    ON outbox_events (next_attempt_at, sequence_id)
    WHERE published_at IS NULL;

CREATE INDEX outbox_events_by_tenant_aggregate_created
    ON outbox_events (tenant_id, aggregate_id, created_at);

CREATE FUNCTION protect_outbox_event() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'outbox events cannot be deleted';
    END IF;

    IF ROW(
        NEW.sequence_id,
        NEW.id,
        NEW.tenant_id,
        NEW.aggregate_type,
        NEW.aggregate_id,
        NEW.event_type,
        NEW.payload,
        NEW.created_at
    ) IS DISTINCT FROM ROW(
        OLD.sequence_id,
        OLD.id,
        OLD.tenant_id,
        OLD.aggregate_type,
        OLD.aggregate_id,
        OLD.event_type,
        OLD.payload,
        OLD.created_at
    ) THEN
        RAISE EXCEPTION 'outbox event contents are immutable';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER outbox_events_immutable
    BEFORE UPDATE OR DELETE ON outbox_events
    FOR EACH ROW EXECUTE FUNCTION protect_outbox_event();
