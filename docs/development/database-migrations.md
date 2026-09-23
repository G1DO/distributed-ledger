# Database migrations

Flyway loads migrations from `service/src/main/resources/db/migration`. Migrations are
forward-only: do not edit `V1__init.sql` after it has been applied; add a later migration for a
correction.

V1 is PostgreSQL-specific. It uses JSONB, stored generated columns, identity columns, roles, and
privilege revocation, so schema integration tests run against PostgreSQL 16 rather than H2.

The migration creates `migrator` as table owner and `app_role` for application DML. In the local
Compose topology Flyway connects as `ledger` and the application connects as `app_role`. If a
local `pgdata` volume predates the migration's role setup, remove that local volume with
`docker compose down -v` before recreating the stack. This deletes the local Compose database.

## Release schema rollout (V2)

`V2__release_terminal_states.sql` extends the reservation status check to allow `RELEASED` and
`EXPIRED`, preserving `RESERVED` and `COMMITTED`. `EXPIRED` is reserved for O2-3; V2 does not
add lazy expiry or a reaper. The migration also documents `expires_at IS NULL` as never expires.
It does not backfill deadlines or change existing reservations, and Reserve still stores NULL.
V1 and the capacity checks and append-only audit permissions remain unchanged.

Stop all old application instances before applying V2, then start the Release-capable version.
The old application must not run against the expanded schema because it does not recognize the
new terminal states. In a deployment where application startup runs Flyway, stop old instances
before starting the new version; its migrations run before it serves requests. Do not overlap
old and new application versions during this rollout.

Rollback requires a new forward compensating migration and a compatible application version.
Never edit V1/V2, undo Flyway history, or restart the old application against the expanded schema.
If new terminal states have been persisted, compensation must preserve their accounting and
audit history; do not relabel them as active reservations to restore the old check.
