# Distributed Ledger — O1 Invariants (ledger reading, I1–I6)

Status: O1-3 baseline. I7–I14 explicitly OUT OF SCOPE for v1.

> Mapping note: the original log-model wording (`POST /append`, I1 append-only … I6
> read-your-commit) is superseded for O1 by the ledger DB reading below. The six tables
> (`account / capacity / reservation / operation / audit_entry / outbox`) are the
> enforcement point — see `docs/adr/DEC-LEDGER-03-schema.md` and
> `docs/adr/DEC-LEDGER-04-concurrency.md`. `operation.request_hash`,
> `operation.response_body`, and `audit_entry.id` carry the old I2/I5/I3 guarantees.

Conventions: each invariant is falsifiable — it states a check that can fail.

## I1 — Nonnegative capacity

`capacity.available = total - reserved - committed` is always `>= 0`.
Enforced by app check + `CHECK(capacity_available_nonnegative)` + `SELECT ... FOR UPDATE`.
Falsifier: any row with `available < 0`, or a reserve that overdraws and commits.

## I2 — Conservation

Reserve moves `available -> reserved`; commit moves `reserved -> committed` with
`SUM(reserved+committed)` stable across commit. No creation or destruction of units.
Falsifier: `SUM` drift after commit, or `total` changing outside a capacity change.

## I3 — Audit append-only

Every successful reserve/commit inserts exactly one `audit_entry` with before/after
snapshots; `audit_entry` rows are never updated or deleted
(`REVOKE UPDATE, DELETE ON audit_entry FROM app_role`, SQLState `42501` on violation).
Falsifier: mutated/deleted audit row, or a committed operation without exactly one audit row.

## I4 — Stable identity + at-most-once effect

`operation.idempotency_key` is `UNIQUE`; `request_hash = sha256(canonical body)` decides replay.
Same key+same hash returns the stored `response_body` byte-identical with no second effect;
same key+different hash fails `422`. Concurrent same-key writers converge to one winner
(`23505` loser path re-reads the winner).
Falsifier: two operations sharing one key, a replay creating a second reservation/audit row,
or replay returning different bytes.

## I5 — COMMITTED survives declared failures

A `200/201` with an operation id means the business + outbox + audit rows are durable in the
same local tx and survive restart; a rolled-back (killed mid-tx) attempt leaves no partial
rows, no orphan `reservation` without `operation`, and capacity sums consistent.
Falsifier: committed entry lost after restart, or partial rows after a kill-mid-tx rollback.

## I6 — Single-tenant isolation of worker/logs

O1 runs as a single principal with bound parameters; one account's reserve/commit never
changes another account's `capacity`, and `GET /v1/query` / `GET /v1/operations/{key}` only
reflect the addressed account/key.
Falsifier: cross-account capacity change, or a query returning another account's state.

## OUT OF SCOPE for v1 (I7–I14)

I7–I14 (expiry reaper, auth, transfer/release, observability SLOs,
multi-region replication, control-plane API, live migration, privacy)
are explicitly OUT OF SCOPE for v1 and MUST NOT be assumed by clients.
