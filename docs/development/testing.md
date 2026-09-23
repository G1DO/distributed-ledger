# Development and testing

Use JDK 25, Docker, `unzip` on Unix, and the checked-in Maven wrapper. From `service/`, run:

```bash
./mvnw clean verify -Pstrict
```

The wrapper pins Maven 3.9.11 and validates its ZIP download with SHA-256. On Unix, `unzip`
must be installed: wrapper 3.3.4 otherwise switches archives to TAR.GZ, which correctly fails
the ZIP checksum. The Docker build installs `unzip`; do not remove checksum verification.
The `validate` phase
requires Java 25 and that Maven version, before compilation. `strict` additionally rejects
SNAPSHOT dependencies, including transitive dependencies. Verification also runs Spotless,
Checkstyle, and ArchUnit. Do not suppress Mockito's static agent or enable preview APIs.

JDK 26 is a deliberately opt-in compatibility experiment:

```bash
# Requires a JDK 26 JAVA_HOME; still compiles to release 25.
./mvnw verify -Pstrict,compat26
```

Ordinary application and health tests use the H2 test configuration. Database integration tests
use a shared Testcontainers PostgreSQL 16 container and verify schema constraints, audit
permissions, idempotency (including concurrent writers), transactional rollback, the outbox, and
the Reserve, Commit, Release, Transfer, and Expire slices. Docker must be available for those integration tests.
The H2 test configuration disables the expiry scheduler. PostgreSQL tests activate the `it`
profile, which enables it; the shared `PostgresITBase` overrides the flag to `false` for
deterministic fixture isolation. Expiry tests invoke real sweeps directly, while
`ExpirySchedulerIT` explicitly enables scheduling and closes its context after the test.

`ProcessCrashIT` uses a separate disposable PG16 container with `fsync`, `synchronous_commit`,
and `full_page_writes` explicitly enabled. It starts the packaged application JAR in child JVMs
and kills/restarts only those processes and its own container, never the Compose database.
Its PostgreSQL recovery log and application logs are saved under `target/process-crash/`.

CI runs the full command, documentation taxonomy check, and O2 Compose drill on JDK 25.
The same required job also enforces corresponding specification updates and runs the separate
[sequential KV verification](kv-verification.md): bounded TLC checks, deterministic Rust tests,
and two independently checked 10,000-operation replays.
It also has an allowed-failure JDK 26 compatibility lane and an
informational OSV scan. The Docker image build skips integration tests (`-DskipITs`) because it
does not run Docker-in-Docker; use the wrapper command above for the full gate. CI retains
Surefire/Failsafe XML, process logs, and O2 CSV/seed/repro/Compose evidence for 30 days; copy release evidence to durable storage
before those artifacts expire. A green compatibility/security advisory lane is not a required
security release gate yet (O3).

`main` protection requires a pull request, an up-to-date successful `build-jdk25` check from
GitHub Actions, and resolved conversations, including for administrators; force pushes and
branch deletion are disabled. No second collaborator is configured, so the numeric approval
requirement is currently zero. This does not satisfy the separate O1/outcome review gate by
itself; CI success and an approved architectural review are different evidence.

## O1 verification scope

- `IdempotencyLockRaceIT` forces both contenders into DB lock waits before releasing the
  business-row blocker; it detects replay being incorrectly rejected after a competing write
  and proves a waiting request takes over the key after its original owner rolls back.
- `InvariantCheckerIT` runs eight reproducible 80-step histories. Capacity is checked against
  an independent expected state, reservation records, and audit effects after each command.
  Three negative controls prove that nonnegative generated `available` is not sufficient.
- `KillMidTxIT` covers injected exceptions and connection loss; `ProcessCrashIT` adds actual
  application and database process failure. See the [failure matrix](../operations/runbooks/o1-crash-matrix.md).

These O1 checks remain alongside the O2 system gate below. Neither establishes tenant
isolation, PITR, host-power-loss durability, or production readiness. Tests requiring
a packaged JAR must run via `verify`, not just `test` or `failsafe:integration-test` directly.

## Release verification scope

`ReleaseIT` covers Release accounting, stored-response replay, validation and terminal-state
failures, audit/outbox effects, and rollback after terminating the transaction's database
connection. `CommitReleaseRaceIT` forces PostgreSQL lock waits to exercise competing Commit
and Release commands and concurrent Release requests. `ReleaseMigrationIT` upgrades a populated
V1 schema through V2 and V3 and checks data preservation, the expiry index, status constraints,
and existing database guards.
Its V3 upgrade stage checks that legacy rows remain unchanged and the expiry index exists.
These tests cover recognized terminal states; the expiry-specific tests below cover the worker
and deadline evaluation.

Run the focused integration tests from `service/` on JDK 25 with Docker available:

```bash
./mvnw -Dit.test=ReleaseIT,CommitReleaseRaceIT,ReleaseMigrationIT verify -Pstrict
```

Use the full `clean verify -Pstrict` command above for the repository gate.

## Transfer verification scope

`TransferAtomicityIT` checks accounting with existing reservations, integer limits and overflow,
same-account rejection, missing accounts, byte-identical replay/lookup, and one audit/outbox
effect per committed transfer. It forces a lower-UUID lock wait while checking that the higher
UUID remains lockable, then exercises opposite-direction transfers and checks `SUM(total)`
conservation. UUID fixtures span Java's signed comparison boundary to detect a different lock order.
Connection termination after the real source debit and after the outbox insert checks full
rollback and same-key recovery, including the absence of orphan outbox rows. Test output records
byte-identical first/replay/lookup evidence and the actual before/after `SUM(total)` query results.

```bash
./mvnw -Dit.test=TransferAtomicityIT verify -Pstrict
```

These are bounded PostgreSQL 16 concurrency and database-session failure tests. They do not add
expiry, tenant isolation, or host-power-loss durability coverage. The full repository gate also
runs the existing reservation, release, and process-crash tests.

## Expiry verification scope

`LazyExpiryIT` exercises PostgreSQL deadline storage, TTL validation, NULL semantics, account
queries, and due Commit/Release behavior. `ExpiryReaperIT` covers repeated and concurrent sweeps,
terminal races, bounded background work, and atomic audit/outbox effects.
Terminating the expiry transaction's PostgreSQL connection checks rollback and retry without
duplicate effects; this does not simulate a reaper OS-process kill.
Expiry fixtures use database timestamps and explicit lock coordination; deterministic cases
disable scheduling and invoke the real sweep directly. `ExpirySchedulerIT` separately exercises
the enabled scheduler. Operation lookup and replay must retain the original stored response
after expiry, and a rejected due Commit/Release must leave no client operation claim behind.

```bash
./mvnw -Dit.test=LazyExpiryIT,ExpiryReaperIT,ExpirySchedulerIT verify -Pstrict
```

These are bounded PostgreSQL integration checks. They do not establish distributed scheduler
coordination or external delivery of expiry events. Run the full `clean verify -Pstrict` gate
for interactions with the existing slices.

## O2 system gate and reproducible histories

`O2ContentionIT` releases 100 callers from a common barrier into the HTTP handler and PG16
transactions, using distinct keys for one-unit reserves against 50 available. Exactly 50 `201`
and 50 `409` responses are required; any unexpected response or unfinished worker fails the
gate. `target/o2-contention/<seed>/` keeps raw CSV (including response bytes/errors), environment,
repro command, and final counters. No transport failures are injected in this gate.

`O2GeneratedHistoryIT` runs 1,000 histories by default, starting at seed `4210421`. Each history
has two fresh 100-unit accounts, 13 batches of one to three concurrent calls, terminal and
transfer concurrent batches, expiry, replay/mismatch and unknown outcomes, plus seeded random mixtures.
It invokes the real transactional services against PG16. An independent state machine explores
all serial orders within a batch and requires one order to explain both responses and persisted
capacity/reservation state. `InvariantChecker` separately reconciles reservation and audit sums,
transfer deltas from known initial totals, and one audit/outbox per completed O2 effect.
`O2InvariantCheckerIT` supplies corruption controls; `GeneratedHistoryTest` exercises the model,
reproducer round-trip and shrinker.

A client timeout is unknown, never a business rejection: lookup followed by identical-key replay
must settle it before boundary checks. A missing lookup alone cannot prove rollback. Deliberate
unknowns cover loss before dispatch (absent lookup) and after completion (stored lookup); these
are controlled test-driver scenarios, not physical network faults.
Workers must finish before fixture cleanup. Expiry fixtures set selected deadlines due using
PostgreSQL time; the real-time TTL/scheduler path is separately covered by Compose.
The history context sets a test-only 20-second PostgreSQL statement timeout. Unresolved worker
or recovery failures fail the history; an internal Expire timeout waits for its original worker
because there is no client key to replay. Dedicated race tests force database lock waits;
the generated batches use a common caller start barrier.

`target/o2-histories/seeds.csv` retains each seed, command/batch count, result and elapsed time.
Failures preserve the symbolic input and observed trace before attempting bounded deletion
shrinking. Only candidates reproducing the same failure are retained. The reduced history/trace
reports the attempt count and `deletionMinimal` flag: false means the budget ran out; even true
only describes batch/command/deadline deletion under the observed schedules, not a globally
smallest counterexample. Seeded inputs do not guarantee the same thread schedule on another run.

From `service/`:

```bash
# Full 1,000-history corpus plus exact contention and existing fault/race gates.
./mvnw -Pstrict -Dit.test=O2ContentionIT,O2GeneratedHistoryIT,O2InvariantCheckerIT,TransferAtomicityIT,CommitReleaseRaceIT,ExpiryReaperIT verify
# Focused reproduction; a reduced count is not the 1,000-history exit gate.
./mvnw -Pstrict -Dit.test=O2GeneratedHistoryIT -Dledger.history.seed=4210421 -Dledger.history.count=1 verify
./mvnw -Pstrict -Dit.test=O2GeneratedHistoryIT -Dledger.history.repro=target/o2-histories/failure-4210421-minimal.history verify
```

Archive failure files before `clean`, which removes `target/`. Copy an exact recorded repro file
outside `target/` to retain it across clean builds. The
[configuration reference](../reference/configuration.md#verification-controls) lists harness overrides;
the [system runbook](../operations/runbooks/o2-system-verification.md) adds real Compose recovery,
documentation taxonomy, artifact retention, and the merged-main/Notion outcome gate.
