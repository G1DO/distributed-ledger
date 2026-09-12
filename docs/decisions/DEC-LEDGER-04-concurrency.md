# DEC-LEDGER-04 — O1 Concurrency Choice (FOR UPDATE)

Status: accepted. Date: 2026-09-12.

## Context

O1-3 (`POST /v1/reserve`, `POST /v1/commit`) needs correct concurrent behavior now, without
prejudging a future concurrency benchmark. DEC-LEDGER-03 explicitly said to investigate and
document the choice rather than silently hard-mandating `FOR UPDATE`.

## Decision

1. **O1-3 uses `SELECT ... FOR UPDATE` on the `capacity` row (plus `reservation` row on commit)
   inside a single `@Transactional` (READ_COMMITTED).** Reserve locks capacity, checks
   `available >= amount`, then inserts `operation + reservation + outbox + audit_entry` same tx.
   Commit locks `reservation` then `capacity`, transitions `RESERVED -> COMMITTED`, moves
   `reserved -> committed` keeping `SUM` stable.
2. **Why not optimistic now:** `capacity.version` exists and is incremented on every update for
   forward-compat, but O1-3 has no retry loop; a bare `UPDATE ... WHERE version=?` would turn
   contention into silent `0-row` updates that still need mapping to `409`/retry. Pessimistic
   locking keeps the first correct implementation small and reviewable.
3. **Why not `SERIALIZABLE` now:** stronger isolation would push serialization failures to every
   caller and require a retry harness that belongs to the O1-4 investigation, not the first slice.
4. **Idempotency race is handled separately from capacity locking:** `operation.idempotency_key`
   is `UNIQUE`. Concurrent same-key writers both miss the pre-check; the loser blocks on the
   unique index until the winner commits, gets `23505`, rolls back its tx (no partial rows), then
   re-reads the winner's stored `response_body` (byte-identical, Postgres-JSONB-normalized) or
   returns `422` on hash mismatch. No savepoints inside the tx — the retry read runs after rollback.
5. **DB `CHECK(available>=0)` stays the backstop:** app-level `available < amount -> 409` covers
   the single-writer case; two racers that both pass the app check serialize on the row lock, and
   any residual overdraw (e.g. lock skipped in future refactor) still fails `23514 -> 409`.

## O1-4 investigation result

The 32-writer PostgreSQL 16 experiment compares this approach with a version-guarded optimistic
update and `SERIALIZABLE` plus bounded retry. The evidence, including the deliberately reproduced
lost-update and multi-row write-skew controls, is in
[the O1 concurrency investigation](../perf/o1-concurrency.md).

The result does not change this decision: pessimistic locking completed every writer without
deadlock and needs no caller-visible retry protocol. The optimistic experiment intentionally
returns conflicts on stale versions; the serializable experiment needs a retry policy. Neither is
a silent replacement for the O1 API.

## Consequences

- O1-3 is correct under contention with minimal machinery; p99/deadlock tuning is O1-4 scope.
- If the O1-4 bench forces a change (optimistic / serializable), it lands as a new PR + ADR note,
  never a silent scope change. `capacity.version` is already maintained for that path.
- Tests proving the choice: `ReserveCommitSliceIT`, `IdempotencyConcurrentIT` (5x same key),
  `OverCapacity409IT`, `KillMidTxIT` — all Testcontainers PG16.
