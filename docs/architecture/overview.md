# Architecture overview

The repository implements one Spring Boot service (`service/`) backed by PostgreSQL. The service
uses `JdbcTemplate`; the domain package is kept independent of Spring and persistence libraries.
`docker-compose.yml` starts PostgreSQL 16 and the application for local use.

## Components and flow

`POST /v1/reserve` locks an account's capacity row, checks available capacity, then writes an
operation, reservation, audit entry, and outbox row in one local transaction. `POST /v1/commit`
locks the reservation and its capacity row, changes `RESERVED` to `COMMITTED`, moves the amount
from `reserved` to `committed`, and writes its operation, audit entry, and outbox row in the same
transaction. Query endpoints read persisted capacity or a stored operation response.

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
