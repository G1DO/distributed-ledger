# DEC-LEDGER-06 — Global lock ordering for multi-account operations and authoritative expiry clock

Status: accepted for Milestone O2 design baseline. Date: 2026-09-13.

## Context

Milestone O2 introduces two-account capacity transfers (`POST /v1/transfer`), reservation releases (`POST /v1/release`), and time-based reservation expirations. 

Concurrently executing operations introduce two major race hazards:
1. **Transfer Deadlocks**: If Tx1 transfers from Account A to Account B while Tx2 concurrently transfers from Account B to Account A, locking rows in argument order ($A \to B$ vs $B \to A$) results in PostgreSQL deadlock (`SQLState 40P01`).
2. **Clock Skew in Expiry**: Distributed application nodes running independent JVM system clocks will disagree on whether a reservation has reached its expiration deadline (`expires_at`), causing inconsistent expiry and commit decisions across instances.

## Decision

1. **Deterministic Multi-Account Lock Ordering**:
   - All transactions follow the strict hierarchical lock sequence:
     $$\text{Operation Claim} \longrightarrow \text{Reservation Row Lock} \longrightarrow \text{Capacity Row Lock(s)}$$
   - When locking multiple capacity rows (as in `Transfer`), account IDs must always be sorted and locked in ascending lexicographical order by UUID:
     $$\min(\text{fromAccountId}, \text{toAccountId}) \longrightarrow \max(\text{fromAccountId}, \text{toAccountId})$$
   - Self-transfers ($\text{fromAccountId} = \text{toAccountId}$) are rejected at request validation (`400 Bad Request`) before any locks are acquired.

2. **Database-Authoritative Expiration Clock**:
   - The PostgreSQL transaction timestamp (`now()`) is designated as the sole authoritative clock for evaluating expiration deadlines.
   - Reservations with `expires_at IS NULL` are explicitly defined as "never expire" to ensure backwards compatibility with O1 records.
   - The scheduled expiry reaper uses `FOR UPDATE SKIP LOCKED` in bounded batches to avoid blocking active user transactions.

## Consequences and Limits

- Deadlocks between opposite-direction transfers are eliminated at the database locking layer.
- Clock skew between application containers does not affect expiration semantics.
- All lock acquisitions remain bounded within short single-transaction scopes.
- Bounded admission, distributed cluster schedulers, and queue-based expiry notifications remain future scopes (O4/Outbox).

## References

- RFC: [O2 Transfer Accounting, Lock Ordering, and Expiry Semantics](../design/rfcs/o2-transfer-expiry.md)
- Invariants: [Ledger Invariants](../design/specifications/ledger-invariants.md)
- Decisions: [DEC-LEDGER-04 (Concurrency)](DEC-LEDGER-04-concurrency.md), [DEC-LEDGER-05 (Operation Claim)](DEC-LEDGER-05-operation-claim.md)
