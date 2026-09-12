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
the reserve-to-commit slice. Docker must be available for those integration tests.

`ProcessCrashIT` uses a separate disposable PG16 container with `fsync`, `synchronous_commit`,
and `full_page_writes` explicitly enabled. It starts the packaged application JAR in child JVMs
and kills/restarts only those processes and its own container, never the Compose database.
Its PostgreSQL recovery log and application logs are saved under `target/process-crash/`.

CI runs the full command on JDK 25. It also has an allowed-failure JDK 26 compatibility lane and an
informational OSV scan. The Docker image build skips integration tests (`-DskipITs`) because it
does not run Docker-in-Docker; use the wrapper command above for the full gate. CI retains
Surefire/Failsafe XML and process logs for 30 days; copy release evidence to durable storage
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
  application and database process failure. See the [failure matrix](../runbooks/o1-crash-matrix.md).

These are bounded O1 tests, not the planned 1,000 concurrent/shrinkable O2 histories, tenant
isolation, PITR, host-power-loss durability, or a production-readiness certificate. Tests requiring
a packaged JAR must run via `verify`, not just `test` or `failsafe:integration-test` directly.
