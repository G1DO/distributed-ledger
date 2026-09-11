-- V1: ledger schema + append-only audit + outbox.
-- Forward-only: never edit after merge, fix via V2.
-- Roles: migrator owns tables (Flyway), app_role is DML-only.
-- Passwords below are local/dev only; later secret-management work replaces them.

DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'migrator') THEN
    CREATE ROLE migrator LOGIN PASSWORD 'migrator';
  END IF;
END
$$;

DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'app_role') THEN
    CREATE ROLE app_role LOGIN PASSWORD 'app';
  END IF;
END
$$;

CREATE TABLE account (
  id UUID PRIMARY KEY,
  display_name TEXT NOT NULL
);

CREATE TABLE capacity (
  account_id UUID PRIMARY KEY REFERENCES account (id) ON DELETE RESTRICT,
  total INT NOT NULL CHECK (total >= 0),
  reserved INT NOT NULL DEFAULT 0 CHECK (reserved >= 0),
  committed INT NOT NULL DEFAULT 0 CHECK (committed >= 0),
  available INT GENERATED ALWAYS AS (total - reserved - committed) STORED,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT capacity_available_nonnegative CHECK (available >= 0)
);

CREATE TABLE operation (
  id UUID PRIMARY KEY,
  idempotency_key TEXT NOT NULL UNIQUE,
  type TEXT NOT NULL,
  status TEXT NOT NULL,
  request_hash TEXT NOT NULL,
  response_body JSONB
);

CREATE TABLE reservation (
  id UUID PRIMARY KEY,
  account_id UUID NOT NULL REFERENCES account (id) ON DELETE RESTRICT,
  operation_id UUID NOT NULL REFERENCES operation (id) ON DELETE RESTRICT,
  amount INT NOT NULL CHECK (amount > 0),
  status TEXT NOT NULL CHECK (status IN ('RESERVED', 'COMMITTED')),
  expires_at TIMESTAMPTZ
);

CREATE TABLE audit_entry (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  operation_id UUID NOT NULL REFERENCES operation (id) ON DELETE RESTRICT,
  account_id UUID NOT NULL REFERENCES account (id) ON DELETE RESTRICT,
  kind TEXT NOT NULL,
  amount INT,
  before_snapshot JSONB,
  after_snapshot JSONB
);

CREATE TABLE outbox (
  id UUID PRIMARY KEY,
  aggregate TEXT NOT NULL,
  payload JSONB NOT NULL,
  dispatched BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX reservation_account_idx ON reservation (account_id);
CREATE INDEX audit_entry_operation_idx ON audit_entry (operation_id);
CREATE INDEX audit_entry_account_idx ON audit_entry (account_id);
CREATE INDEX outbox_undispatched_idx ON outbox (dispatched) WHERE dispatched = FALSE;

ALTER TABLE account OWNER TO migrator;
ALTER TABLE capacity OWNER TO migrator;
ALTER TABLE operation OWNER TO migrator;
ALTER TABLE reservation OWNER TO migrator;
ALTER TABLE audit_entry OWNER TO migrator;
ALTER TABLE outbox OWNER TO migrator;

GRANT USAGE ON SCHEMA public TO app_role, migrator;

GRANT SELECT, INSERT, UPDATE ON account TO app_role;
GRANT SELECT, INSERT, UPDATE ON capacity TO app_role;
GRANT SELECT, INSERT, UPDATE ON reservation TO app_role;
GRANT SELECT, INSERT, UPDATE ON operation TO app_role;
GRANT SELECT, INSERT, UPDATE ON outbox TO app_role;
GRANT SELECT, INSERT ON audit_entry TO app_role;
GRANT USAGE, SELECT ON SEQUENCE audit_entry_id_seq TO app_role;

-- Append-only: last line of defense for I3, even if a future GRANT widens access.
REVOKE UPDATE, DELETE ON audit_entry FROM app_role;
