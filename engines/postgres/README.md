# engines/postgres — durable store (O1-3)

Canonical schema is `service/src/main/resources/db/migration/V1__init.sql`: six tables
`account / capacity / reservation / operation / audit_entry / outbox`, forward-only via Flyway.

- `capacity.available` is `GENERATED ALWAYS AS (total - reserved - committed) STORED` with
  `CHECK(available >= 0)`; `operation.idempotency_key` is `UNIQUE`;
  `REVOKE UPDATE, DELETE ON audit_entry FROM app_role` enforces append-only.
- O1-3 access is `service/.../infra/JdbcLedgerRepository.java` via `JdbcTemplate` as `app_role`
  (bound params only). Business row + outbox + audit commit in one `@Transactional`
  (`service/ReserveService.java`, `service/CommitService.java`).
- Concurrency: `SELECT ... FOR UPDATE` on `capacity`/`reservation`, `CHECK` backstop —
  see `docs/adr/DEC-LEDGER-04-concurrency.md`. No relay worker (outbox table-only).
