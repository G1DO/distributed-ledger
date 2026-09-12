# DEC-LEDGER-05 — Claim operation identity before business locks

Status: accepted for the approved O1 hardening increment. Date: 2026-09-12.

## Reproduced defect

At baseline `effa5db`, two requests can both miss the operation pre-check. The loser then
observes exhausted capacity or a committed reservation and returns `409`, before the insert
that would have detected a replay. Three forced lock-race tests reproduced this: same-key
Reserve at the last unit, same-key Commit, and different-body reuse at the capacity edge.

## Decision

Use the existing unique operation index as the cross-process coordinator. Within the command's
explicit `READ COMMITTED` transaction, insert an internal `PENDING` claim with
`ON CONFLICT (idempotency_key) DO NOTHING`, before acquiring business locks. An insert that
loses waits for the other transaction; the next statement can see its completed row and checks
both type and request hash before returning the stored response. If the owner rolls back,
PostgreSQL permits the waiting insert to claim the key instead.

The claim, business writes, audit, outbox, and stored `COMPLETED` response share one transaction.
`OperationCoordinator` requires an existing transaction (`MANDATORY`); it must never be invoked
through a separate `REQUIRES_NEW` transaction. There is no persisted job state, lease, cache,
polling loop, or JVM-local lock. Validation still precedes claiming. Future authorization must
precede replay. V1's schema is unchanged; its migration is not rewritten.

## Consequences and limits

- Lock order is operation → capacity (Reserve), or operation → reservation → capacity (Commit).
  O2 must extend this order explicitly for two-account commands.
- `READ COMMITTED` is part of the protocol; repeatable-read/serializable would need a different
  fresh-read/retry policy. A committed pending row indicates an invariant breach, not a replay.
- Failed commands do not retain their key or error response. HTTP success is sent only after
  Spring's transactional interceptor commits. A transport failure remains an unknown outcome.
- This addresses at-most-once committed effects, not exactly-once delivery or principal scoping.
- Contention holds DB connections. Bounded admission, lock/statement deadlines, and overload
  measurements remain O4, not a claim that this implementation handles unbounded load.

Proof: `IdempotencyLockRaceIT`, `IdempotencyConcurrentIT`, `KillMidTxIT`, and `ProcessCrashIT`.
