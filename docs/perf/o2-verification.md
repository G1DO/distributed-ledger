# O2 verification evidence

Pre-publication local evidence for [issue #16](https://github.com/G1DO/distributed-ledger/issues/16).
These results alone do not mark the outcome Verified. The final merged commit, CI run, artifact
links, and outcome status are maintained in the
[Notion verification record](https://app.notion.com/p/3d60a821b3cc81adb31ac9d6941aab71).

## Source and environment

The local implementation starts at merged O2-3 commit
[`b9b8001ca8846265f89368e1efb78cc44c59a188`](https://github.com/G1DO/distributed-ledger/commit/b9b8001ca8846265f89368e1efb78cc44c59a188).
Its existing [green main CI run](https://github.com/G1DO/distributed-ledger/actions/runs/35804154958)
predates this harness and does not prove this change passed CI.
Local runs use Temurin 25.0.4.1, Maven wrapper 3.9.11, PostgreSQL 16, Docker Engine 29.4.1,
and Docker Compose 5.1.3 on Linux. Local verification ran against the uncommitted implementation.

Reproduce with the [system verification runbook](../operations/runbooks/o2-system-verification.md).
Record exact CI commit/run and downloadable artifact URLs in the linked outcome record after
publication; the local artifact paths below are not published URLs.

## Observations

Gate 1 passed with seed `160421`: 100 distinct-key attempts, exactly 50 `201` and 50 `409`,
with final `(total, reserved, committed, available) = (50, 50, 0, 0)` and matching reservation,
operation, audit, and outbox counts. The [raw CSV](o2-contention-2026-09-23.csv) is retained here;
the full environment/repro file is `service/target/o2-contention/160421/environment.txt`.

The real Compose drill passed in **180.705 seconds** including the image build, with **28 recorded
HTTP interactions and 9 completed operations**. It used Temurin 25.0.4+7 and PostgreSQL 16.10 with
`fsync`, `synchronous_commit`, and `full_page_writes` enabled. Mid-Commit PostgreSQL termination
returned HTTP `500`; the driver treated that as unknown until recovery, exact six-table rollback
comparison, operation lookup `404`, and successful same-key retry. After another PostgreSQL
crash and application restart, the acknowledged rows and original 181 response bytes survived
unchanged. Cleanup succeeded. All 85 build-input hashes and the archived driver match this tree.
Artifacts: `service/target/o2-compose-e2e/20260923T023159Z-0f6c03b9/`.

Two preceding Compose attempts remain marked failed: a broken local Docker Desktop buildx
plugin path prevented the first build, and source changes during a warm image build correctly
invalidated the second run. The successful run used a temporary Docker client plugin-path
override, recorded in `docker-client-override.txt`; no host or repository configuration workaround
was applied. These are harness/environment failures, not observed ledger regressions.

Gate 5 passed all **1,000 histories**, seeds `4210421` through `4211420`, with **13,000 batches
and 23,121 logical commands**, zero model/invariant violations. Both pre-dispatch unknown and
post-completion lost-response resolution run in every history. The
[per-history seed/result CSV](o2-histories-2026-09-23.csv) is retained here. No failing history
required reduction in this run; shrink behavior and bounded/minimality reporting were verified
by the dedicated unit tests. A separate targeted history verifies that an unknown mismatched
request resolves to `422` while preserving the stored winner.

The complete JDK 25 command `./mvnw -B verify -Pstrict -Ddebug=false` passed in **5 min 56 sec**:
**12 unit tests and 106 integration tests**, zero failures/errors/skips. This includes the existing
Transfer backend-termination, Commit/Release/Expire races, repeated/concurrent reaper sweeps,
and application/PostgreSQL process-crash tests. Spotless, Checkstyle, ArchUnit, release-25
compilation, and strict dependency/toolchain enforcement passed. `-Ddebug=false` only suppresses
environment-enabled debug logging. Logs, environment, and test counts are retained under
`service/target/o2-evidence/`, with JUnit XML in the usual Surefire/Failsafe directories.

`python3 scripts/check-docs.py` passed. An isolated fixture confirmed it rejects an empty
documentation category and a broken local link. Both Python entrypoints parsed successfully,
and the final diff passed whitespace and independent correctness/contract review.

## Review, limitations, and follow-up

An independent agent reviewed the existing Transfer/operation-claim implementation and its
fault/race tests at the base commit. It found no material correctness defect. The review checked
claim-before-lock ordering, PostgreSQL UUID lock order, overflow/availability checks under lock,
same-transaction debit/credit/audit/outbox/response, and type/hash-checked stored replay. This was
static review; executable evidence comes from the test runs.

The [Notion O2 Verification & learning record](https://app.notion.com/p/3d60a821b3cc81adb31ac9d6941aab71)
now contains these observed local results, evidence locations, reproduction commands, limitations,
conclusion, learning, and follow-up. At the time of this local run the outcome was unverified
and the milestone remained open; consult the linked record for final release status.

The proof is bounded to one service, PostgreSQL 16, and a single principal. Seeds reproduce
workloads, not exact OS scheduling. Generated histories do not inject physical network faults;
unknown-response scenarios lose dispatch/replies and resolve by lookup/replay. Process-kill tests
exercise database/application recovery, not host power loss or PITR.

Learning: independently passing slices need aggregate checks across all terminal states and
transfers, with unresolved responses kept separate from business rejection. Exact counts and
independent models detect drift that generated `available >= 0` alone cannot detect. Preserved
failure inputs and minimized repros make failures actionable.

Release completion requires publishing the implementation, green JDK 25 CI on merged main,
durably retained evidence, and exact commit/run/artifact URLs in the Notion record before
marking O2 Verified or closing the milestone. An uncoached operator drill has not been
performed; its duration and O4's under-ten-minute objective are unmeasured. No separate
production deployment was performed or required for this development milestone.
