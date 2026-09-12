# Development and testing

Use JDK 25 and Maven 3.6.3 or later. From `service/`, run:

```bash
mvn verify -Pstrict
```

The strict profile enforces Java 25 through 26 and bans SNAPSHOT dependencies. The verification
build also runs Spotless, Checkstyle, and ArchUnit.

Ordinary application and health tests use the H2 test configuration. Database integration tests
use a shared Testcontainers PostgreSQL 16 container and verify schema constraints, audit
permissions, idempotency (including concurrent writers), transactional rollback, the outbox, and
the reserve-to-commit slice. Docker must be available for those integration tests.

CI runs this command on JDK 25. It also has an allowed-failure JDK 26 compatibility lane and an
informational OSV scan. The Docker image build skips integration tests (`-DskipITs`) because it
does not run Docker-in-Docker; use the Maven command above for the full gate.
