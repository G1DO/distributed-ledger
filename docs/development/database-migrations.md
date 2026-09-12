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
