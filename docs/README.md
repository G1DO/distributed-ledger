# Technical documentation

- [Architecture overview](architecture/overview.md) — implemented service, database, and data flow.
- [API](api/overview.md) — HTTP contract and idempotency behavior.
- [Ledger invariants](design/specifications/ledger-invariants.md) — capacity, atomicity, audit, and idempotency guarantees.
- [Sequential KV contract](design/specifications/kv/README.md) — GET/PUT/DELETE/CAS semantics and the canonical TLA+ model.
- [KV verification](development/kv-verification.md) — Rust/Python conformance, bounded TLC checks, specification synchronization, and evidence.
- [Development and testing](development/testing.md) — build, test, and integration-test workflow.
- [Database migrations](development/database-migrations.md) — Flyway and PostgreSQL constraints.
- [Configuration](reference/configuration.md) — runtime variables and local Compose defaults.
- [Security model](security/security-model.md) — current trust boundaries and limitations.
- [O1 concurrency investigation](perf/o1-concurrency.md) — 32-writer comparison and raw result.
- [O1 crash matrix](operations/runbooks/o1-crash-matrix.md) — transaction failure and replay proof.
- [O2 expiry reaper](operations/runbooks/o2-expiry-reaper.md) — scheduling, recovery, and rollback.
- [O2 system verification](operations/runbooks/o2-system-verification.md) — exit gates, Compose recovery, and evidence retention.
- [O2 verification evidence](perf/o2-verification.md) — observations and remaining milestone verification work.
- [Decisions](decisions/) — accepted architectural decisions.
- [O2 Design RFC](design/rfcs/o2-transfer-expiry.md) — transfer accounting, lock ordering, and expiry semantics.
