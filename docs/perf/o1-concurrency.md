# O1 concurrency investigation

Date: 2026-09-12. Database: PostgreSQL 16 Testcontainers. Harness:
`ConcurrencyInvestigationIT`, run with JDK 25 using `mvn verify -Pstrict`.

## What was tested

Each strategy starts 32 simultaneous writers against one capacity row with 10,000 available
units and asks each writer to reserve one unit.

| Strategy | Transaction behavior | Result policy |
| --- | --- | --- |
| A — pessimistic | `SELECT ... FOR UPDATE`, then increment | every writer waits and succeeds |
| B — optimistic | read `version`, then `UPDATE ... WHERE version = ? AND available >= ?` | a zero-row update is recorded as a conflict; no retry is hidden |
| C — serializable | `SERIALIZABLE` read/update with at most eight retries | retry SQLSTATE `40001`, otherwise surface the failure |

The harness also has deterministic negative controls. Two unprotected read-modify-write
transactions both read `reserved = 0` and end at `1` (lost update). Two unprotected transactions
read an aggregate predicate across separate capacity rows and each update a different row, ending
at `200` when the cross-row policy allows only `100` (write skew). Those experiments describe
unsafe alternatives; the shipped reserve operation affects one capacity row.

## Observed result and decision

The raw one-run result rows are in [o1-bench-2026-09-12.csv](o1-bench-2026-09-12.csv). Timings
are microseconds measured around a writer's transaction and are environment-sensitive; use them
for comparison on this host, not an SLO.

Strategy A completed all 32 writers with no deadlocks. Strategy B intentionally returns conflicts
under simultaneous stale-version writers, so it is not a drop-in replacement without a retry and
an HTTP conflict policy. Strategy C completes only by retrying serialization failures and adds
failure handling that O1 does not otherwise need. We therefore retain A: it is the smallest
reviewable implementation that preserves capacity and gives callers a normal success result under
this contention level.

This is not a permanent performance choice. Re-run the harness on the target deployment before
changing the control; any such change needs a retry/error-mapping design and a decision update.

## Database backstops

Application code locks the capacity row, but it is not the only protection. PostgreSQL enforces
`CHECK (available >= 0)` (`capacity_available_nonnegative`); `CapacityCheckIT` proves an attempted
overdraw fails with SQLSTATE `23514`, and `OverCapacity409IT` proves the API maps ordinary
over-capacity reserve to `409` with no operation/reservation/audit rows. This is checked against
PostgreSQL 16, never H2.
