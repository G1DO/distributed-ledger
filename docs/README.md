# Technical documentation

- [Architecture overview](architecture/overview.md) — implemented service, database, and data flow.
- [API](api/overview.md) — HTTP contract and idempotency behavior.
- [Ledger invariants](design/specifications/ledger-invariants.md) — capacity, atomicity, audit, and idempotency guarantees.
- [Development and testing](development/testing.md) — build, test, and integration-test workflow.
- [Database migrations](development/database-migrations.md) — Flyway and PostgreSQL constraints.
- [Configuration](reference/configuration.md) — runtime variables and local Compose defaults.
- [Security model](security/security-model.md) — current trust boundaries and limitations.
- [O1 concurrency investigation](perf/o1-concurrency.md) — 32-writer comparison and raw result.
- [O1 crash matrix](runbooks/o1-crash-matrix.md) — transaction failure and replay proof.
- [Decisions](decisions/) — accepted architectural decisions.
