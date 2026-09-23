# Ledger invariants

The six-table ledger model (`account`, `capacity`, `reservation`, `operation`, `audit_entry`,
and `outbox`) is the enforcement point. See [DEC-LEDGER-03](../../decisions/DEC-LEDGER-03-schema.md)
and [DEC-LEDGER-04](../../decisions/DEC-LEDGER-04-concurrency.md) for the accepted schema and
concurrency decisions.

Conventions: each invariant is falsifiable — it states a check that can fail.
Reserve, Commit, Release, Transfer, and lazy/scheduled Expire are implemented.

## I1 — Nonnegative capacity

`capacity.available = total - reserved - committed` is always `>= 0`, and `0 <= total <= INT_MAX`.
Enforced by app check + `CHECK(capacity_available_nonnegative)` + `CHECK(total >= 0)` + `SELECT ... FOR UPDATE`.
Transfer enforces source $\text{available}_S \ge A$ and destination $\text{total}_D + A \le \text{INT\_MAX}$.
The overflow check uses `total_D > INT_MAX - A` and returns `400` before either capacity write;
source insufficiency returns `409`. Transfer amounts must be JSON integers in `1..INT_MAX`.
Falsifier: any row with `available < 0` or `total < 0`, an overdrawn reserve/transfer, or an integer overflow.

## I2 — Conservation

- Reserve moves `available -> reserved`.
- Commit moves `reserved -> committed` with `SUM(reserved+committed)` stable across commit.
- Release moves `reserved -> available` by the reservation amount (`committed` and `total`
  unchanged); Expire has the same capacity effect.
- Transfer moves $\text{total}_S \to \text{total}_D$ with $\Delta \text{total}_S + \Delta \text{total}_D = 0$.
  Each account's `reserved` and `committed` remain unchanged; its `available` changes with `total`.
  Same-account transfers fail `400` before claiming identity or acquiring business locks.
Across all operations, units are neither arbitrarily created nor destroyed. System-wide $\sum \text{total}$ is constant across transfers.
For Reserve, Commit, Release, and Expire, `available + reserved + committed = total` remains constant;
`reserved + committed` decreases on Release or Expire by exactly the reservation amount.
Falsifier: an incorrect counter delta after commit/release/expire, unexplained `total` change,
or aggregate ledger drift across transfer.
Evidence compares counters with reservation records and audit effects, plus a test-side model
that starts from known totals and tracks each accepted command independently. The generated
`available` equation alone cannot detect a double debit. Negative controls deliberately inject
counter drift, an absent audit effect, and an unexplained total change and must fail the checker.

## I3 — Audit append-only

Each first successful reserve, commit, release, expire, or transfer effect inserts exactly one `audit_entry` with before/after
snapshots; an idempotent replay creates none. The application database role cannot update or delete audit rows
(`REVOKE UPDATE, DELETE ON audit_entry FROM app_role`, SQLState `42501` on violation).
Falsifier: mutated/deleted audit row, or a committed operation without exactly one audit row.
Transfer uses one audit row anchored to the source account, with both account IDs and counters
under `from` and `to` in each snapshot, plus one outbox row. Replays insert neither.

## I4 — Stable identity + at-most-once effect

`operation.idempotency_key` is `UNIQUE`; `request_hash = sha256(canonical body)` decides replay.
Same key+same hash returns the stored `response_body` byte-identical with no second effect;
same key+different hash fails `422`. Concurrent same-key writers converge to one winner
by claiming identity before business locks (`ON CONFLICT DO NOTHING`, then a fresh
`READ COMMITTED` read of the winner). The operation type must also match. Failed attempts
roll back their claims; only completed effects retain their keys.
Terminal states (`COMMITTED`, `RELEASED`, `EXPIRED`) are immutable: once reached, concurrent or
subsequent competing terminal requests under a new key fail `409 Conflict`. Replay is resolved
before inspecting reservation state, so a successful same-command replay still returns its
original response. Commit, Release, and Expire serialize on the reservation row before locking
capacity; exactly one can make the terminal transition, with one audit effect and one outbox row.
Falsifier: two operations sharing one key, a replay creating a second reservation/audit row,
replay returning different bytes, or a reservation transitioning out of a terminal state.

## I5 — Committed effects are atomic

A response that creates a new reserve, commit, release, or transfer effect is produced from a row persisted in the
same local transaction as the business, outbox, and audit rows. A rolled-back (killed mid-tx) attempt leaves no partial
rows, no half-transfers, no orphan `reservation` without `operation`, and capacity sums consistent.
Internal Expire effects also commit their operation, reservation, capacity, audit, and outbox
together. A rejected due Commit/Release rolls back its own claim before an independent expiry
transaction; returning `409` does not roll back a completed expiry.
Falsifier: committed entry lost after restart, partial rows after a kill-mid-tx rollback, or debit without credit in transfer.
Transfer locks both capacities in ascending PostgreSQL UUID order before either update, so
opposite-direction requests cannot form a capacity-lock cycle. `TransferAtomicityIT` checks
that order using a blocked lower UUID and a still-lockable higher UUID, verifies total sums
under concurrent transfers, and terminates the live database connection between debit and
credit to check full rollback and same-key recovery.

## I6 — Tenant isolation (planned, not implemented)

I6 retains the original project requirement: callers and workers must not access another
principal's state, including through replay, operation lookup, logs, metrics, caches, or DB
access. Ownership and authorization are O3 scope; the current unauthenticated API does not
meet I6. Account separation tests are not evidence of tenant isolation.

### Supporting O1/O2 check — Account-scoped mutation

One account's reserve/commit/release/expire never changes another account's `capacity`, and transfer only changes the two specified accounts by equal amounts. `GET /v1/query` expires only the addressed account's due reservations before reading its capacity;
`GET /v1/operations/{key}` only reads the addressed key. This is not tenant isolation:
the API currently has no caller authentication or authorization.
Falsifier: cross-account capacity change outside transfer, or a query returning another account's state.

## I7 — Expiry deadline enforcement

A new Commit or Release cannot accept a reservation with `expires_at <= now()`, where `now()` is
that command's PostgreSQL transaction-start timestamp, including when the transaction waits for
a lock. Reserve sets the deadline to its transaction timestamp plus `ttlSec` seconds.
`expires_at IS NULL` means never expires, including all pre-existing NULL rows.

Lazy access (Commit, Release, or account capacity query) and scheduled sweeps transition due
`RESERVED` rows to `EXPIRED`, freeing reserved capacity (`reserved -= amount`, `available += amount`).
Terminal states remain unchanged. Each expiry commits one internal operation, one `EXPIRE` audit,
and one outbox event; repeated or concurrent sweeps add no second effect. Background lock skips
or failures defer a candidate to a later sweep. Stored responses and replays remain immutable.
Falsifier: a new command accepting a deadline due at its transaction start, a completed expiry
with an incorrect capacity delta or missing/duplicate effects, or a successfully processed due
reservation remaining `RESERVED`.

## Planned / Not implemented

HTTP authentication and authorization (O3), observability dashboards and alerts (O4), outbox relay worker to external queues, replication, control-plane APIs, live migration, and privacy controls are not implemented and must not be assumed by callers.
See [RFC: O2 Transfer Accounting, Lock Ordering, and Expiry Semantics](../rfcs/o2-transfer-expiry.md) and [DEC-LEDGER-06](../../decisions/DEC-LEDGER-06-lock-order-expiry-clock.md) for O2 design specifications.
