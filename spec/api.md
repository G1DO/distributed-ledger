# Distributed Ledger — v1 Tiny API

No control-plane API in v1 per REQ-V1-03. Endpoints: append / read / snapshot / restore only (+ stub `/health`, `/ready`).

## Headers

- `Idempotency-Key` (required on `POST /append`): opaque client token, `1..64` chars.
  - Same key + identical body → echo stored result, no duplicate (see I2/I5).
  - Same key + different body → `409 Conflict` with error body.
- `Content-Type: application/json` on POSTs.

## Body echo rule

Every mutating response echoes the committed request body fields plus
server-assigned `id` and `sequence`. Replays echo the original stored
result byte-for-byte (excluding transport headers).

## Endpoints (v1 stub shapes; no logic yet)

### `POST /append`

Request:

```json
{ "payload": "opaque-bytes-or-json", "metadata": {} }
```

- `201` committed: `{ "id": "uuid", "sequence": 42, "payload": "...", "metadata": {} }`
- `200` replay (same key+body): identical body to original `201`, status `200`.
- `409` key reuse with different body.
- `400` missing/invalid key or body.

### `GET /read?fromSequence=N&limit=K`

- `200`: `{ "entries": [{ "id": "...", "sequence": N, "payload": "..." }], "nextSequence": M }`
- Ordered by `sequence` ascending. Empty list when caught up.

### `POST /snapshot`

- `201`: `{ "snapshotId": "uuid", "upToSequence": N }`

### `POST /restore`

Request: `{ "snapshotId": "uuid" }`

- `200`: `{ "restoredUpToSequence": N }`

### `GET /health`, `GET /ready` (skeleton stub)

- `200`: `{ "status": "UP" }`. No auth. `/ready` does not gate on DB.
