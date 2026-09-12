# O1 crash matrix

Run the deterministic proof on PostgreSQL 16:

```bash
cd service
mvn -Dit.test=KillMidTxIT verify
```

`KillMidTxIT` injects failures into the real `ReserveService` transaction through a production
no-op transaction probe. It proves every pre-commit row is rolled back, then replays the same key
to create exactly one reserve. The connection-loss case closes an uncommitted PostgreSQL session;
PostgreSQL aborts that transaction before releasing its locks. The after-commit case deliberately
discards the first response and proves the same key returns the stored response.

| Point | Mechanism | Expected durable state | Same-key replay |
| --- | --- | --- | --- |
| before transaction | no service call | no rows; capacity unchanged | creates one reserve |
| after capacity lock | failure probe | no rows; capacity unchanged | creates one reserve |
| after operation insert | failure probe | no rows; capacity unchanged | creates one reserve |
| after reservation insert | failure probe | no rows; capacity unchanged | creates one reserve |
| after outbox insert | failure probe | no operation/reservation/audit/outbox; capacity unchanged | creates one reserve |
| after commit, before response | caller drops response | one complete reserve and audit | returns byte-identical stored result |
| after disconnect | close uncommitted JDBC connection | no rows; capacity unchanged | creates one reserve |

For each matrix row the test verifies `available >= 0`,
`total = available + reserved + committed`, no orphan reservation/audit row, and at-most-once
effect. `InvariantCheckerIT` adds an 80-step deterministic random reserve/commit/replay/mismatch
history across two accounts and checks O1 invariants I1–I6 after every operation.
