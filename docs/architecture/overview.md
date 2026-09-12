# Architecture overview

The repository implements one Spring Boot service (`service/`) backed by PostgreSQL. The service
uses `JdbcTemplate`; the domain package is kept independent of Spring and persistence libraries.
`docker-compose.yml` starts PostgreSQL 16 and the application for local use.

## Components and flow

Both commands first claim the unique operation key inside an explicit `READ COMMITTED`
transaction. `INSERT ... ON CONFLICT DO NOTHING` waits for a concurrent owner; a subsequent
statement reads its completed response or rejects a different command/body with `422`.
Replay is resolved before inspecting mutable business state.

For a new operation, `POST /v1/reserve` locks capacity and checks available units; commit locks
the reservation, then capacity, and moves `reserved` to `committed`. Business, audit, outbox,
and the completed stored response commit together. The internal `PENDING` claim never commits
on its own; an exception or disconnected transaction rolls it back and frees the key for retry.
Query endpoints read persisted capacity or a stored operation response. See
[the operation-claim decision](../decisions/DEC-LEDGER-05-operation-claim.md).

The outbox is storage only: records default to `dispatched=false`, and no relay worker is
implemented. `control-plane/`, `simulation/`, and the non-PostgreSQL engine directories contain
no implemented runtime path.

## Persistent model

The canonical schema is the forward-only Flyway migration
[`V1__init.sql`](../../service/src/main/resources/db/migration/V1__init.sql). It creates:

- `account` and one `capacity` row per account;
- `reservation` and `operation` for the reserve/commit flow and idempotent replay;
- `audit_entry` for before/after snapshots; and
- `outbox` for transactional event staging.

`capacity.available` is a stored generated value: `total - reserved - committed`. Database check
constraints prevent negative capacity; row locks serialize the current implementation's capacity
updates. The detailed behavioral guarantees are in the [ledger invariants](../design/specifications/ledger-invariants.md).
