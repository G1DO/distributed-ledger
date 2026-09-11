# Distributed Ledger — v1 Spec Invariants (I1–I6)

Status: v1 baseline for O1. I7–I14 explicitly OUT OF SCOPE for v1.

> Schema note: the ledger schema (`account / capacity / reservation /
> operation / audit_entry / outbox`) is the DB-level reading of I1–I6 —
> see `docs/adr/DEC-LEDGER-03-schema.md` for the mapping. API shapes
> (`POST /v1/reserve`, `POST /v1/commit`) land in O1-3.

Conventions: each invariant is falsifiable — it states a check that can fail.

## I1 — Append-only

Once an entry is committed, it is never mutated or deleted in v1.
Falsifier: any committed entry whose stored bytes differ on a later read,
or any committed entry missing on a later read (outside snapshot/restore).

## I2 — Idempotent append

`POST /append` with the same `Idempotency-Key` and identical body returns
the identical result without creating a second entry.
Same key with a different body MUST fail (409 Conflict).
Falsifier: replaying key+body creates a second entry, or returns a
different entry id / different response.

## I3 — Global ordering

Every committed entry has a unique, monotonically increasing sequence number.
No two committed entries share a sequence number; no gaps are visible to readers.
Falsifier: two entries with the same sequence, or a read observing
sequence `n+1` without `n`.

## I4 — Durability on commit

A response of committed (2xx with entry id + sequence) means the entry is
durable: it survives process restart and is returned by subsequent reads.
Falsifier: committed entry lost after restart, or not visible to reads.

## I5 — Single commit decision

For a given `Idempotency-Key`, at most one entry is ever committed.
Concurrent appends with the same key converge to a single winner;
losers observe the winner's result (or 409 on body mismatch per I2).
Falsifier: two different entries committed under the same key.

## I6 — Read-your-commit consistency

A successful commit is immediately visible to subsequent reads
(snapshot and point reads) on the same store.
Falsifier: read after commit does not include the committed entry
(absent crash/recovery window defined by I4).

## OUT OF SCOPE for v1 (I7–I14)

I7–I14 (expiry reaper, auth, transfer/release, observability SLOs,
multi-region replication, control-plane API, live migration, privacy)
are explicitly OUT OF SCOPE for v1 and MUST NOT be assumed by clients.
