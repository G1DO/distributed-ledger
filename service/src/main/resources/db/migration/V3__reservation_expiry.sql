-- No deadline backfill: legacy NULL deadlines remain never-expiring.
CREATE INDEX reservation_due_idx ON reservation (expires_at, id)
  WHERE status = 'RESERVED' AND expires_at IS NOT NULL;

COMMENT ON COLUMN reservation.expires_at IS
  'NULL means never expires; otherwise expires when expires_at <= PostgreSQL transaction now().';
