# Schema, roles, and spec mapping

Status: accepted. Date: 2026-09-11.

## Context

The planned ledger model (`account / capacity / reservation /
operation / audit_entry / outbox`) sits next to `spec/api.md` and
`spec/invariants.md` on `main`, which describe a log model (`POST /append`,
`GET /read?fromSequence`, I1 append-only … I6 read-your-commit). The invariant
numbers I1/I3/I4 are reused in the plan with ledger meanings (nonnegative,
audit-immutability, stable-operation-identity). A reviewer diffing `V1`
against `spec/` would flag a mismatch even when the SQL is correct.

## Decision

1. **The six-table list is canonical for this step.** `V1__init.sql` creates exactly
    the 6 tables above. No `RELEASED`/`EXPIRED` statuses (they come with release/expiry),
    no endpoints/logic beyond the slice, no relay worker (outbox table only).
2. **Spec mapping (not a spec rewrite):**
   - `reservation + operation` ≡ append entry (`operation.idempotency_key` ≡
     `Idempotency-Key` header, `operation.request_hash` ≡ canonical-body hash).
   - `audit_entry.id` (identity, monotonic) ≡ `sequence` for ordering (I3).
   - `operation.response_body` persisted before commit ≡ body-echo rule.
   - Ledger I1 (nonnegative `available`) + I3 (audit append-only) + I4
     (stable identity / at-most-once) are the DB-level readings of spec
      I1/I2/I5; spec wording stays until the API shapes are updated to
      `POST /v1/reserve`, `POST /v1/commit`, `GET /v1/query`.
3. **Roles in SQL:** `V1__init.sql` creates `migrator` (table owner, Flyway)
   and `app_role` (DML only). `REVOKE UPDATE, DELETE ON audit_entry FROM
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
- `spec/invariants.md` carries a footnote pointing here until the API shapes are aligned
  in the spec. No I7–I14 claims in v1 tests.
