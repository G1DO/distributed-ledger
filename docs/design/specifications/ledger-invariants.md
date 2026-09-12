# Ledger invariants

The six-table ledger model (`account`, `capacity`, `reservation`, `operation`, `audit_entry`,
and `outbox`) is the enforcement point. See [DEC-LEDGER-03](../../decisions/DEC-LEDGER-03-schema.md)
and [DEC-LEDGER-04](../../decisions/DEC-LEDGER-04-concurrency.md) for the accepted schema and
concurrency decisions.

Conventions: each invariant is falsifiable — it states a check that can fail.

## I1 — Nonnegative capacity

`capacity.available = total - reserved - committed` is always `>= 0`.
Enforced by app check + `CHECK(capacity_available_nonnegative)` + `SELECT ... FOR UPDATE`.
Falsifier: any row with `available < 0`, or a reserve that overdraws and commits.

## I2 — Conservation

Reserve moves `available -> reserved`; commit moves `reserved -> committed` with
`SUM(reserved+committed)` stable across commit. No creation or destruction of units.
Falsifier: `SUM` drift after commit, or `total` changing outside a capacity change.
Evidence compares counters with reservation records and audit effects, plus a test-side model
that starts from known totals and tracks each accepted command independently. The generated
`available` equation alone cannot detect a double debit. Negative controls deliberately inject
counter drift, an absent audit effect, and an unexplained total change and must fail the checker.

## I3 — Audit append-only

Each first successful reserve or commit effect inserts exactly one `audit_entry` with before/after
snapshots; an idempotent replay creates none. The application database role cannot update or delete audit rows
(`REVOKE UPDATE, DELETE ON audit_entry FROM app_role`, SQLState `42501` on violation).
Falsifier: mutated/deleted audit row, or a committed operation without exactly one audit row.

## I4 — Stable identity + at-most-once effect

`operation.idempotency_key` is `UNIQUE`; `request_hash = sha256(canonical body)` decides replay.
Same key+same hash returns the stored `response_body` byte-identical with no second effect;
same key+different hash fails `422`. Concurrent same-key writers converge to one winner
by claiming identity before business locks (`ON CONFLICT DO NOTHING`, then a fresh
`READ COMMITTED` read of the winner). The operation type must also match. Failed attempts
roll back their claims; only completed effects retain their keys.
Falsifier: two operations sharing one key, a replay creating a second reservation/audit row,
or replay returning different bytes.

## I5 — Committed effects are atomic

A response that creates a new reserve or commit effect is produced from a row persisted in the
same local transaction as the business, outbox, and audit rows. A rolled-back (killed mid-tx) attempt leaves no partial
rows, no orphan `reservation` without `operation`, and capacity sums consistent.
Falsifier: committed entry lost after restart, or partial rows after a kill-mid-tx rollback.

## I6 — Tenant isolation (planned, not implemented)

I6 retains the original project requirement: callers and workers must not access another
principal's state, including through replay, operation lookup, logs, metrics, caches, or DB
access. Ownership and authorization are O3 scope; the current unauthenticated API does not
meet I6. Account separation tests are not evidence of tenant isolation.

### Supporting O1 check — Account-scoped mutation

One account's reserve/commit never changes another account's `capacity`, and `GET /v1/query` /
`GET /v1/operations/{key}` only reflect the addressed account/key. This is not tenant isolation:
the API currently has no caller authentication or authorization.
Falsifier: cross-account capacity change, or a query returning another account's state.

## Not implemented

Expiry, transfer/release, HTTP authentication and authorization, observability, replication,
control-plane APIs, live migration, and privacy controls are not implemented and must not be
assumed by callers.
