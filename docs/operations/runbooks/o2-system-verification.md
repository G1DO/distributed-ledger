# O2 system verification

This is the exit sequence for [issue #16](https://github.com/G1DO/distributed-ledger/issues/16).
Slice completion alone does not mark O2 Verified. Run the complete sequence against the integrated
commit, preserve the artifacts, and fill the outcome's Verification & learning record.

## Run and retain

From the repository root, with JDK 25, Docker Engine/Compose, Python 3, and curl:

```bash
cd service
./mvnw clean verify -Pstrict
cd ..
python3 scripts/check-docs.py
python3 scripts/o2-compose-e2e.py
```

The Compose drill creates its own project, fresh database volume, and host ports. It exercises
Reserve, Commit, Release, Transfer, scheduled Expire, account queries, and stored-response replay.
It holds an outbox table lock, observes a Commit transaction waiting after its business writes,
kills that project's PostgreSQL process, and restarts it. Assertions require the original
reservation/counters and no partial Commit operation/audit/outbox; retry then commits once.
An acknowledged result must survive another database restart and replay byte-identically.
Cleanup removes only the drill's project and volume. Do not substitute a shared database.

The JDK 25 CI job runs all three commands and uploads `verification-jdk25`, including:

- `surefire-reports/` and `failsafe-reports/`: assertions, test counts, and failures;
- `o2-contention/`: seed, raw per-attempt CSV, environment, and final capacity;
- `o2-histories/`: seeds/results, and full/minimized repros on failure;
- `o2-compose-e2e/`: HTTP bodies, SQL snapshots, crash/restart logs, and environment;
- `o2-evidence/`: exact source commit, worktree status, tool versions, and CI URL;
- `process-crash/`: existing O1 application/PostgreSQL failure evidence.

Copy the artifact to durable release storage before the 30-day CI retention expires. A seed
reproduces commands and inputs; OS/database scheduling can vary. Preserve failed runs even
if a retry passes. An incomplete or unresolved run never counts as a passed gate.

## Exit checklist

| Gate | Executable evidence | Required observation |
| --- | --- | --- |
| 1: capacity edge | `O2ContentionIT` | 100 concurrent distinct-key one-unit requests, 50 initial available, exactly 50 `201` and 50 `409`, no injected failures; reserved 50, committed 0, available 0; one audit/outbox per success and no rejected claims |
| 2: no half-transfer | `TransferAtomicityIT.killedConnectionRollsBackAndUnobservedRetryResolvesThroughLookup` | Backend terminated after debit and after outbox insert; balances restored, no partial effects, lookup/replay resolves retry |
| 3: one terminal state | `CommitReleaseRaceIT`, `ExpiryReaperIT` | Both Commit/Release winner orders, expiry winning, and a pre-deadline command winning; one terminal effect |
| 4: reaper idempotence | `ExpiryReaperIT.overlappingSweepsSkipOwnedRowsAndEachExpireExactlyOnce` | Overlapping sweeps and another sweep leave one expiry audit/outbox per reservation |
| 5: generated histories | `O2GeneratedHistoryIT`, `InvariantChecker` | 1,000 seeded histories, concurrent batches, zero model/invariant violations; shrinking and unknown-response resolution enabled |
| Real environment | `scripts/o2-compose-e2e.py` | Full lifecycle and coordinated PostgreSQL crash/recovery pass on actual Compose |
| Technical docs | `scripts/check-docs.py` | Nonempty categories, valid local links, one canonical RFC with legacy runbook redirects |

Gate 1 uses MockMvc through the real HTTP handler and Testcontainers PostgreSQL 16; it measures
application contention without a TCP transport fault model. Compose supplies real HTTP and
process recovery. Generated-history expiry fixtures make deadlines due using PostgreSQL time;
Compose separately checks elapsed TTL with the enabled scheduler.

## Record and conclude

Record the exact merged commit, green JDK 25 CI URL, artifact URLs, environment, repro commands,
observed counts, limitations, independent Transfer/idempotency review, learning, and follow-up
in the [O2 outcome](https://app.notion.com/p/3d60a821b3cc81adb31ac9d6941aab71).
Only then mark the outcome Verified and close the milestone. Local dirty-tree results are
implementation evidence, not the merged-main exit record. See the
[evidence record](../../perf/o2-verification.md) for observations and remaining release work.

An automated drill is not a blind operator drill. Have another operator follow this runbook
without coaching and record elapsed time and recovery confusion. Until then, the blind-drill
duration and O4's under-ten-minute recovery objective remain unmeasured; this does not block O2.
Auth, observability, relay, PITR, distributed scheduling, and host-power-loss recovery remain
outside this proof.

## Upgrade and rollback

No separate production deployment is required for this development milestone. Stop the old
application, apply forward-only migrations through V3, then start one expiry-capable node.
Startup Flyway migrates before serving requests in Compose. Retain applied migrations and all
committed accounting/audit history during application rollback; terminal states are irreversible.
Disabling the scheduler leaves lazy expiry enabled. See the [expiry runbook](o2-expiry-reaper.md)
and [migration ordering](../../development/database-migrations.md).
