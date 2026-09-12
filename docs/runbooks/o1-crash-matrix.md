# O1 crash matrix

Run transaction-failure and process-crash tests on PostgreSQL 16:

```bash
cd service
./mvnw -Dit.test=KillMidTxIT,ProcessCrashIT verify -Pstrict
```

`KillMidTxIT` injects failures into the real `ReserveService` transaction through a production
no-op transaction probe. It proves every pre-commit row is rolled back, then replays the same key
to create exactly one reserve. The connection-loss case closes an uncommitted PostgreSQL session;
PostgreSQL aborts that transaction before releasing its locks. The after-commit case deliberately
discards the first response and proves the same key returns the stored response.

| Point | Mechanism | Expected durable state | Same-key replay |
| --- | --- | --- | --- |
| before transaction | no service call | no rows; capacity unchanged | creates one reserve |
| after operation insert | failure probe | no rows; capacity unchanged | creates one reserve |
| after capacity lock | failure probe | no rows; capacity unchanged | creates one reserve |
| after reservation insert | failure probe | no rows; capacity unchanged | creates one reserve |
| after outbox insert | failure probe | no operation/reservation/audit/outbox; capacity unchanged | creates one reserve |
| after commit, response discarded | test caller drops response | one complete reserve and audit | returns byte-identical stored result |
| after disconnect | close uncommitted JDBC connection | no rows; capacity unchanged | creates one reserve |

The operation claim now precedes the capacity lock; the matrix retains the checkpoint names
but reflects their actual order. These injected exceptions and closed connections are useful
rollback evidence, not OS-process-crash tests.

## Real processes and WAL recovery

`ProcessCrashIT` starts the packaged Java 25 application over HTTP and a separate disposable
PG16 container. It enables and asserts `fsync=on`, `synchronous_commit=on`, and
`full_page_writes=on` (the usual Testcontainers PG defaults disable fsync). A held outbox table
lock and observed `pg_stat_activity` wait deterministically place an in-flight reserve after its
business/audit writes but before completion; no timing-only sleep chooses the fault boundary.

| Scenario | Actual mechanism | Verified result |
| --- | --- | --- |
| In-flight application loss | forcibly terminate child JVM at outbox insert | no partial state; restart creates one reserve; another restart replays it |
| In-flight PostgreSQL loss | Docker KILL on owned PG container, restart same data directory | WAL recovery removes uncommitted effects; retry creates one reserve |
| Acknowledged commit then PG loss | receive HTTP 201, kill PG, restart PG and app | stored response and business/audit/outbox effect survive; retry returns 200 |
| Client never reads response | send raw HTTP, observe durable commit, terminate app without client reading | lookup state survives; restarted application replays identical response |

The unobserved-response case does not synchronize a kill literally between DB COMMIT and
server socket write. It proves client uncertainty can be resolved from persisted identity.
These tests do not simulate host power loss, storage-controller failure, replication, outbox
delivery, or PITR. Later outcomes must supply those separately declared guarantees.

Evidence: `target/failsafe-reports/TEST-com.g1do.ledger.ProcessCrashIT.xml`, application logs and
`postgres.log` under `target/process-crash/` (including interrupted startup and WAL redo), uploaded
by CI. No user database/container is a target; test-owned containers are removed on teardown.

`InvariantCheckerIT` adds eight seeded 80-step reserve/commit/replay/mismatch histories across
two accounts. It checks independent expected counters, reservation totals, audit effects, and
O1 structural invariants after each command. Negative controls detect accounting corruption.
I6 tenant isolation is explicitly not implemented or claimed by these account-separation tests.
