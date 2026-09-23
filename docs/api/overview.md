# Ledger API

The implemented endpoints are `POST /v1/reserve`, `POST /v1/commit`, `POST /v1/release`, `POST /v1/transfer`, `GET /v1/query`,
`GET /v1/operations/{key}`, `GET /health`, and `GET /ready`. The service has no HTTP
authentication or authorization layer.

## Headers

- `Idempotency-Key` (required on every POST): opaque client token, `1..64` chars.
  Must equal body `idempotencyKey`, otherwise `400`.
  - Same key + same command and canonical body → echo stored `response_body` byte-for-byte, no second effect.
  - Same key + different command or body → `422` with error body, no state change.
- `Content-Type: application/json` on POSTs.

## Body echo + hashing rule

- `request_hash = sha256(canonical JSON)` is computed server-side before any replay decision:
  - reserve canonical: `{"accountId":"...","amount":N,"idempotencyKey":"...","ttlSec":T?}`
    (keys sorted, `ttlSec` omitted when null).
  - commit canonical: `{"idempotencyKey":"...","reservationId":"..."}`.
  - release canonical: `{"idempotencyKey":"...","reservationId":"..."}`.
  - transfer canonical: `{"amount":N,"fromAccountId":"...","idempotencyKey":"...","toAccountId":"..."}` (keys sorted).
- Every mutating response persists `response_body` (JSONB) before commit and replays the stored
  text verbatim. Postgres JSONB normalizes formatting on write; the first response is read back
  from the stored row, so first / replay / `GET /operations/{key}` are byte-identical.
- All SQL uses bound parameters. `accountId` / `reservationId` must be UUIDv4, `amount > 0`.
- Keys are currently global, not principal-scoped. Claiming a key precedes business locks, so
  a valid replay still succeeds after capacity is exhausted or its reservation is terminal.
  Commit and Release have the same canonical body shape but different operation types; reusing
  a Commit key for Release, or vice versa, returns `422` even when the hashes match.
  A failed transaction does not retain its key. This is at-most-once committed effect, not
  exactly-once network delivery; a timeout must be resolved by lookup or same-key retry.

## Endpoints

### `POST /v1/reserve`

Request: `{ "accountId": "uuid-v4", "amount": 100, "idempotencyKey": "opaque-1..64", "ttlSec": 3600? }`

`ttlSec`, when supplied, must be at least 1 and participates in the request hash. It does not yet
set an expiration deadline: reservations are stored with `expires_at = NULL`, meaning never
expires. Lazy expiry and the scheduled reaper remain O2-3 scope.

- `201` first commit: `{ "accountId":"...","amount":100,"idempotencyKey":"...",
  "reservationId":"uuid-v4","status":"RESERVED" }` (JSONB-normalized formatting).
- `200` replay (same key+same hash): identical body, no second reservation.
- `409` over capacity (`available < amount`, app check + `CHECK(available>=0)` backstop).
  No `reservation` / `operation` / `audit_entry` row left behind.
- `422` same key + different hash.
- `400` missing/invalid header, body, UUID, or `amount <= 0`. `404` unknown `accountId`.
- Single `@Transactional(READ_COMMITTED)`: claim operation → resolve replay/mismatch →
  lock `capacity FOR UPDATE` → validate → insert
  `reservation(RESERVED)` + `outbox(dispatched=false)` + exactly one `audit_entry(kind=RESERVE,
  before/after snapshots)` → complete operation with stored response → commit.

### `POST /v1/commit`

Request: `{ "reservationId": "uuid-v4", "idempotencyKey": "opaque-1..64" }`

- `200` first + replay: `{ "accountId":"...","amount":100,"idempotencyKey":"...",
  "reservationId":"...","status":"COMMITTED" }`.
- Replay same key+same hash → original body, no second `audit_entry`, no double decrement.
- `409` reservation already terminal (`COMMITTED`, `RELEASED`, `EXPIRED`) or capacity violated.
- `422` same key + different hash. `404` unknown reservation.
- Atomically `RESERVED -> COMMITTED`: claim operation and resolve replay/mismatch, then lock
  `reservation` + `capacity FOR UPDATE`,
  `UPDATE reservation SET COMMITTED`, `reserved -= amount, committed += amount`
  (`SUM` stable), insert `outbox` + `audit_entry(kind=COMMIT)`, and complete the operation same tx.

### `POST /v1/release`

Request: `{ "reservationId": "uuid-v4", "idempotencyKey": "opaque-1..64" }`

- `200` first + replay: `{ "accountId":"...","amount":100,"idempotencyKey":"...",
  "reservationId":"...","status":"RELEASED" }`.
- Replay same key+same command+same hash → original body, no second audit/outbox row or capacity change.
- `409` reservation already in a terminal state (`COMMITTED`, `RELEASED`, `EXPIRED`) under a new
  key, or a capacity constraint violation.
- `422` same key + different command or hash. `404` unknown reservation.
- `400` missing/invalid header, body, UUIDv4, or a header/body key mismatch.
- Atomically `RESERVED -> RELEASED`: claim operation and resolve replay/mismatch, lock
  `reservation FOR UPDATE`, then `capacity FOR UPDATE` in `READ COMMITTED`, set the reservation
  status to `RELEASED`, and decrement `reserved` by its amount. Generated `available` increases
  by the same amount; `total` and `committed` stay unchanged.
- Exactly one `audit_entry(kind=RELEASE)` with before/after capacity snapshots and one
  `outbox(aggregate='release', dispatched=false)` row are inserted in that transaction. The
  outbox payload contains `accountId`, `amount`, `operationId`, and `reservationId`. The completed
  `operation(type=RELEASE)` stores the response before commit. Failure rolls back the operation
  claim and all effects.
- Commit versus Release on one reservation produces one terminal transition: the winner returns
  `200`; the competing command with a different key returns `409` after observing that state.
  Only the winner changes capacity or inserts audit/outbox rows. Concurrent same-key Releases
  return the same `200` body with one effect.
- `EXPIRED` is recognized as terminal, but this slice does not create expiry transitions or
  evaluate deadlines.

### `POST /v1/transfer`

Request: `{ "fromAccountId": "uuid-v4", "toAccountId": "uuid-v4", "amount": 100, "idempotencyKey": "opaque-1..64" }`

- `200` first + replay: `{ "fromAccountId":"...","toAccountId":"...","amount":100,
  "idempotencyKey":"...","status":"TRANSFERRED" }`.
- Replay same key+same command+same hash → original stored body byte-for-byte, even after
  source capacity is exhausted or the destination reaches its limit. No second transfer effect.
- `409` insufficient source available capacity (`available < amount`).
- `400` destination capacity overflow (`total + amount > 2,147,483,647`). The application checks
  `total > INT_MAX - amount` before either write, without overflowing its own arithmetic.
  PostgreSQL `INT` storage and existing nonnegative capacity `CHECK`s provide the database backstop.
- `422` same key + different command or hash, resolved before account lookup, balance, or overflow
  rejection. Structural request validation precedes the operation claim.
- `400` same-account transfer (`fromAccountId == toAccountId`), invalid UUIDv4, invalid/missing
  key, or a header/body key mismatch. Self-transfers are rejected before claiming a key or locking capacity.
  `amount` must be a JSON integer in `1..2,147,483,647`; null, fractional/exponent notation,
  numeric strings, and out-of-range values are rejected without coercion or truncation.
- `404` unknown `fromAccountId` or `toAccountId`.
- Atomically moves capacity: claim operation → resolve replay/mismatch → lock capacities in ascending UUID order
  `ORDER BY account_id ASC FOR UPDATE` → validate balances → debit source `total -= amount` and credit destination
  `total += amount` → insert outbox + audit → complete operation in one `READ COMMITTED` transaction.
  Both accounts retain their `reserved` and `committed` counters; `available` moves by the same
  amount as `total`, and each capacity version increments once. No reservation is created.
- Exactly one `audit_entry(kind=TRANSFER, account_id=fromAccountId)` records both accounts:
  `before_snapshot` and `after_snapshot` each contain `from` and `to` objects with `accountId`,
  `total`, `reserved`, `committed`, and `available`. One `outbox(aggregate='transfer', dispatched=false)`
  contains `amount`, `fromAccountId`, `toAccountId`, and `operationId` in its payload.
- Failure rolls back both capacity updates and every operation/audit/outbox row. For an
  unobserved response, `GET /v1/operations/{key}` returns the committed body, or `404` when no
  effect committed; retry the same key and body to resolve an in-flight or rolled-back attempt.
  Keys and lookups remain global within the current single-principal service; cross-principal
  isolation is O3 scope.

### `GET /v1/query?accountId=`

- `200`: `{ "accountId":"...","total":N,"reserved":N,"committed":N,"available":N }`.
  It reflects the current persisted capacity. `400` invalid UUIDv4; `404` unknown account.

### `GET /v1/operations/{key}`

- `200`: persisted `response_body` verbatim. `404` unknown key.
- This is an idempotent operation-response lookup, not an audit-entry query.

### `GET /health`, `GET /ready`

- `200`: `{ "status": "UP" }`. No auth. `/ready` does not gate on DB.

## Planned / Not implemented

Expiry (O2-3), HTTP authentication and authorization (O3), metrics/tracing/alert dashboards (O4), and outbox relay worker to message brokers remain planned subsequent outcomes.
See [RFC: O2 Transfer Accounting, Lock Ordering, and Expiry Semantics](../design/rfcs/o2-transfer-expiry.md) and [DEC-LEDGER-06](../decisions/DEC-LEDGER-06-lock-order-expiry-clock.md).
