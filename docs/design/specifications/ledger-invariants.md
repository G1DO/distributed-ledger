# Ledger invariants

The six-table ledger model (`account`, `capacity`, `reservation`, `operation`, `audit_entry`,
and `outbox`) is the enforcement point. See [DEC-LEDGER-03](../../decisions/DEC-LEDGER-03-schema.md)
and [DEC-LEDGER-04](../../decisions/DEC-LEDGER-04-concurrency.md) for the accepted schema and
concurrency decisions.

Conventions: each invariant is falsifiable — it states a check that can fail.
Reserve, Commit, Release, and Transfer are implemented. Expiry effects below describe the
accepted O2 design and remain planned. `EXPIRED` is a recognized terminal state reserved for
O2-3; no current API or worker performs expiry.

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
  unchanged); Expire has the same planned capacity effect.
- Transfer moves $\text{total}_S \to \text{total}_D$ with $\Delta \text{total}_S + \Delta \text{total}_D = 0$.
  Each account's `reserved` and `committed` remain unchanged; its `available` changes with `total`.
  Same-account transfers fail `400` before claiming identity or acquiring business locks.
Across all operations, units are neither arbitrarily created nor destroyed. System-wide $\sum \text{total}$ is constant across transfers.
For Reserve, Commit, and Release, `available + reserved + committed = total` remains constant;
`reserved + committed` decreases on Release by exactly the reservation amount.
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
original response. Commit and Release serialize on the reservation row before locking capacity;
exactly one can make the terminal transition, with one audit effect and one outbox row.
Falsifier: two operations sharing one key, a replay creating a second reservation/audit row,
replay returning different bytes, or a reservation transitioning out of a terminal state.

## I5 — Committed effects are atomic

A response that creates a new reserve, commit, release, or transfer effect is produced from a row persisted in the
same local transaction as the business, outbox, and audit rows. A rolled-back (killed mid-tx) attempt leaves no partial
rows, no half-transfers, no orphan `reservation` without `operation`, and capacity sums consistent.
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

One account's reserve/commit/release never changes another account's `capacity`, and transfer only changes the two specified accounts by equal amounts. `GET /v1/query` /
`GET /v1/operations/{key}` only reflect the addressed account/key. This is not tenant isolation:
the API currently has no caller authentication or authorization.
Falsifier: cross-account capacity change outside transfer, or a query returning another account's state.

## I7 — Expiry deadline enforcement (planned, O2-3)

A reservation with `expires_at IS NOT NULL` where `expires_at <= now()` cannot be committed or released. Any uncommitted expired reservation transitions to `EXPIRED` (via lazy evaluation on access or background scheduled reaper sweep) and frees its reserved capacity (`reserved -= amount`, `available += amount`). `expires_at IS NULL` signifies "never expires".
Falsifier: a reservation committing when `expires_at <= now()`, or an expired reservation leaving capacity locked.

## Planned / Not implemented

Expiry deadline storage/evaluation and the reaper (O2-3), HTTP authentication and authorization (O3), observability dashboards and alerts (O4), outbox relay worker to external queues, replication, control-plane APIs, live migration, and privacy controls are not implemented and must not be assumed by callers.
See [RFC: O2 Transfer Accounting, Lock Ordering, and Expiry Semantics](../rfcs/o2-transfer-expiry.md) and [DEC-LEDGER-06](../../decisions/DEC-LEDGER-06-lock-order-expiry-clock.md) for O2 design specifications.
