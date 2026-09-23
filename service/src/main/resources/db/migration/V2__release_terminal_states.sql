-- O2-1: Release is implemented; EXPIRED is reserved for the O2-3 expiry slice.
-- Stop the old application before applying V2: it cannot read the new terminal states.
ALTER TABLE reservation DROP CONSTRAINT reservation_status_check;
ALTER TABLE reservation ADD CONSTRAINT reservation_status_check
  CHECK (status IN ('RESERVED', 'COMMITTED', 'RELEASED', 'EXPIRED'));

-- Preserve existing deadlines and legacy NULLs; no expiry backfill.
COMMENT ON COLUMN reservation.expires_at IS
  'NULL means never expires; non-NULL deadlines are reserved for O2-3 expiry enforcement.';
