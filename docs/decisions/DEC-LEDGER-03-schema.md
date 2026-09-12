# Schema, roles, and spec mapping

Status: accepted. Date: 2026-09-11.

## Context

The planned ledger model (`account / capacity / reservation / operation / audit_entry / outbox`)
needed an explicit mapping to the API and invariant contracts. The contracts now describe the
implemented reserve, commit, and query slice.

## Decision

1. **The six-table list is canonical for this step.** `V1__init.sql` creates exactly
    the 6 tables above. No `RELEASED`/`EXPIRED` statuses (they come with release/expiry),
    no endpoints/logic beyond the slice, no relay worker (outbox table only).
2. **Contract mapping:**
   - `reservation + operation` ≡ append entry (`operation.idempotency_key` ≡
     `Idempotency-Key` header, `operation.request_hash` ≡ canonical-body hash).
   - `audit_entry.id` (identity, monotonic) ≡ `sequence` for ordering (I3).
   - `operation.response_body` persisted before commit ≡ body-echo rule.
   - Ledger I1 (nonnegative `available`) + I3 (audit append-only) + I4
     (stable identity / at-most-once) are the DB-level readings of spec
     I1/I2/I5; the API shapes are `POST /v1/reserve`, `POST /v1/commit`, and
     `GET /v1/query`.
3. **Roles in SQL:** `V1__init.sql` creates `migrator` (the table owner) and
   `app_role` (DML only). Compose configures Flyway with the existing `ledger` user.
   `REVOKE UPDATE, DELETE ON audit_entry FROM
   app_role` is the append-only enforcement; tests connect as `app_role`
   (owner connections bypass `REVOKE` and give false greens).
4. **PG16-only V1:** `JSONB`, `GENERATED ALWAYS AS (...) STORED`,
   `GENERATED ALWAYS AS IDENTITY`, `REVOKE` are intentional. No H2
   compatibility. Unit `*Test` stays on H2; all DB `*IT` run on real
   Postgres 16 via Testcontainers.
5. **Passwords in V1 (`app` / `migrator`) are local/dev only.** Later secret-management work
   replaces them. `docker-compose.yml` runs Flyway as the
   existing `ledger` superuser and the app as `app_role`; stale `pgdata`
   volumes pre-dating V1 roles require `docker compose down -v`.
6. **Forward-only Flyway:** V1 is never edited after merge; fixes go in V2.

## Consequences

- `mvn verify -Pstrict` runs `*IT` via failsafe on Testcontainers PG16.
- `Dockerfile` build uses `-DskipITs` (no Docker-in-Docker for Testcontainers
  at image build time); CI/local `mvn verify -Pstrict` without the flag is
  the gate that runs the ITs.
- [Ledger invariants](../design/specifications/ledger-invariants.md) carries the current
  contract. No unimplemented features are claimed as test coverage.
