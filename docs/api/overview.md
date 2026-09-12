# Ledger API

The implemented endpoints are `POST /v1/reserve`, `POST /v1/commit`, `GET /v1/query`,
`GET /v1/operations/{key}`, `GET /health`, and `GET /ready`. The service has no HTTP
authentication or authorization layer.

## Headers

- `Idempotency-Key` (required on every POST): opaque client token, `1..64` chars.
  Must equal body `idempotencyKey`, otherwise `400`.
  - Same key + same canonical body → echo stored `response_body` byte-for-byte, no second effect.
  - Same key + different body → `422` with error body, no state change.
- `Content-Type: application/json` on POSTs.

## Body echo + hashing rule

- `request_hash = sha256(canonical JSON)` is computed server-side before any replay decision:
  - reserve canonical: `{"accountId":"...","amount":N,"idempotencyKey":"...","ttlSec":T?}`
    (keys sorted, `ttlSec` omitted when null).
  - commit canonical: `{"idempotencyKey":"...","reservationId":"..."}`.
- Every mutating response persists `response_body` (JSONB) before commit and replays the stored
  text verbatim. Postgres JSONB normalizes formatting on write; the first response is read back
  from the stored row, so first / replay / `GET /operations/{key}` are byte-identical.
- All SQL uses bound parameters. `accountId` / `reservationId` must be UUIDv4, `amount > 0`.

## Endpoints

### `POST /v1/reserve`

Request: `{ "accountId": "uuid-v4", "amount": 100, "idempotencyKey": "opaque-1..64", "ttlSec": 3600? }`

`ttlSec`, when supplied, must be at least 1 and participates in the request hash. V1 does not
persist it or run an expiry process; it has no reservation-expiry effect.

- `201` first commit: `{ "accountId":"...","amount":100,"idempotencyKey":"...",
  "reservationId":"uuid-v4","status":"RESERVED" }` (JSONB-normalized formatting).
- `200` replay (same key+same hash): identical body, no second reservation.
- `409` over capacity (`available < amount`, app check + `CHECK(available>=0)` backstop).
  No `reservation` / `operation` / `audit_entry` row left behind.
- `422` same key + different hash.
- `400` missing/invalid header, body, UUID, or `amount <= 0`. `404` unknown `accountId`.
- Single `@Transactional`: lock `capacity FOR UPDATE` → validate → insert `operation` +
  `reservation(RESERVED)` + `outbox(dispatched=false)` + exactly one `audit_entry(kind=RESERVE,
  before/after snapshots)` → commit.

### `POST /v1/commit`

Request: `{ "reservationId": "uuid-v4", "idempotencyKey": "opaque-1..64" }`

- `200` first + replay: `{ "accountId":"...","amount":100,"idempotencyKey":"...",
  "reservationId":"...","status":"COMMITTED" }`.
- Replay same key+same hash → original body, no second `audit_entry`, no double decrement.
- `409` reservation already `COMMITTED` (second effect impossible) or capacity violated.
- `422` same key + different hash. `404` unknown reservation.
- Atomically `RESERVED -> COMMITTED`: lock `reservation` + `capacity FOR UPDATE`,
  `UPDATE reservation SET COMMITTED`, `reserved -= amount, committed += amount`
  (`SUM` stable), insert `operation(COMMIT)` + `outbox` + `audit_entry(kind=COMMIT)` same tx.
- The implemented state transition is `RESERVED -> COMMITTED`; there is no release, transfer, or
  expiry operation.

### `GET /v1/query?accountId=`

- `200`: `{ "accountId":"...","total":N,"reserved":N,"committed":N,"available":N }`.
  It reflects the current persisted capacity. `400` invalid UUIDv4; `404` unknown account.

### `GET /v1/operations/{key}`

- `200`: persisted `response_body` verbatim. `404` unknown key.
- This is an idempotent operation-response lookup, not an audit-entry query.

### `GET /health`, `GET /ready`

- `200`: `{ "status": "UP" }`. No auth. `/ready` does not gate on DB.

## Not implemented

There is no release/transfer/expiry worker, HTTP authentication or RBAC, metrics/tracing/alerts,
or outbox relay worker. The outbox is table-only and defaults to `dispatched=false`.
