# Architecture overview

The repository implements one Spring Boot service (`service/`) backed by PostgreSQL. The service
uses `JdbcTemplate`; the domain package is kept independent of Spring and persistence libraries.
`docker-compose.yml` starts PostgreSQL 16 and the application for local use.

## Components and flow

Reserve, Commit, Release, and Transfer first claim the unique operation key inside an explicit `READ COMMITTED`
transaction. `INSERT ... ON CONFLICT DO NOTHING` waits for a concurrent owner; a subsequent
statement reads its completed response or rejects a different command/body with `422`.
Replay is resolved before inspecting mutable business state.

For a new operation, `POST /v1/reserve` locks capacity and checks available units. Commit and
Release lock the reservation, then capacity: Commit moves `reserved` to `committed`, while
Release moves `reserved` back to `available` and leaves `total` and `committed` unchanged.
Transfer locks both capacities in global order, then subtracts from source `total` and adds to
destination `total`, leaving both accounts' reservation counters unchanged.
Business, audit, outbox, and the completed stored response commit together. The internal `PENDING` claim never commits
on its own; an exception or disconnected transaction rolls it back and frees the key for retry.
Query endpoints read persisted capacity or a stored operation response. See
[the operation-claim decision](../decisions/DEC-LEDGER-05-operation-claim.md).

The outbox is storage only: records default to `dispatched=false`, and no relay worker is
implemented. `control-plane/`, `simulation/`, and the non-PostgreSQL engine directories contain
no implemented runtime path.

## Lock order

The hierarchy is operation claim → existing reservation, when applicable → capacity rows.
Transfer skips the reservation tier and acquires both capacities with one bound query:

```sql
SELECT account_id, total, reserved, committed, available, version
FROM capacity WHERE account_id IN (?, ?)
ORDER BY account_id ASC FOR UPDATE;
```

PostgreSQL orders UUIDs consistently regardless of source/destination direction. Both opposing
requests lock the lower UUID before the higher UUID, preventing a circular capacity-lock wait.
The immutable account IDs keep this order stable while a `READ COMMITTED` waiter rereads an
updated row. Do not substitute Java's signed `UUID.compareTo` order for PostgreSQL's UUID order.
Single-account operations acquire no second capacity lock and cannot close that cycle.
Transfer neither locks existing reservations nor claims another operation after taking capacity
locks. This extends [DEC-LEDGER-06](../decisions/DEC-LEDGER-06-lock-order-expiry-clock.md).

Balance and overflow checks run under both locks, before debit and credit. The updates, a single
audit with both accounts' before/after snapshots, one outbox event, and the completed operation
share the transaction. A connection loss after debit rolls back the claim and all writes. A
response lost after commit is resolved by operation lookup or a byte-identical same-key replay.
These are single-principal guarantees; global keys do not provide tenant isolation.

## Reservation lifecycle

```mermaid
stateDiagram-v2
    [*] --> RESERVED: POST /v1/reserve
    RESERVED --> COMMITTED: POST /v1/commit
    RESERVED --> RELEASED: POST /v1/release
    RESERVED --> EXPIRED: Planned O2-3 expiry
    COMMITTED --> [*]
    RELEASED --> [*]
    EXPIRED --> [*]
```

Only one terminal transition may commit. A competing Commit or Release waits on the reservation
row, then observes the winner's terminal state and returns `409` without another capacity,
audit, or outbox effect. A same-command replay under the winning key returns the stored body
before acquiring business locks. `EXPIRED` is accepted by the schema and rejected by Commit
and Release as terminal, but no expiry path is implemented. `expires_at = NULL` means never
expires; current Reserve requests still store NULL even when they include `ttlSec`.

## Persistent model

The schema is defined by forward-only Flyway migrations.
[`V1__init.sql`](../../service/src/main/resources/db/migration/V1__init.sql) creates:

- `account` and one `capacity` row per account;
- `reservation` and `operation` for the reservation lifecycle and idempotent replay;
- `audit_entry` for before/after snapshots; and
- `outbox` for transactional event staging.

[`V2__release_terminal_states.sql`](../../service/src/main/resources/db/migration/V2__release_terminal_states.sql)
extends reservation statuses with `RELEASED` and `EXPIRED` and documents NULL expiration semantics
without backfilling existing reservations. See [migration rollout ordering](../development/database-migrations.md).
Transfer reuses the V1/V2 schema: `INT` bounds, nonnegative capacity checks, and the existing
operation, audit, and outbox tables already support it. No migration is rewritten or added.

`capacity.available` is a stored generated value: `total - reserved - committed`. Database check
constraints prevent negative capacity; row locks serialize the current implementation's capacity
updates. The detailed behavioral guarantees are in the [ledger invariants](../design/specifications/ledger-invariants.md).
