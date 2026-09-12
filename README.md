# Distributed Ledger

Spec-first Spring Boot ledger + green CI. O1-3 slice live: `Reserve → Commit → Query`, idempotent.

## 5-min replay

```bash
cd service
mvn verify -Pstrict
docker compose -f ../docker-compose.yml up --build
curl -s localhost:8080/health
curl -s localhost:8080/ready
```

Expected: `{"status":"UP"}` on both endpoints; app + Postgres 16 running.

Seed one account (no account-create endpoint in O1 — single principal):

```bash
docker compose exec postgres psql -U ledger -d ledger -c \
  "INSERT INTO account (id, display_name) VALUES ('11111111-1111-4111-8111-111111111111','demo')"
docker compose exec postgres psql -U ledger -d ledger -c \
  "INSERT INTO capacity (account_id, total, reserved, committed) VALUES \
  ('11111111-1111-4111-8111-111111111111', 1000, 0, 0)"
```

Reserve → commit → query → replay (byte-identical):

```bash
ACCT=11111111-1111-4111-8111-111111111111
RKEY=demo-reserve-1
curl -s -X POST localhost:8080/v1/reserve -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $RKEY" \
  -d "{\"accountId\":\"$ACCT\",\"amount\":100,\"idempotencyKey\":\"$RKEY\"}"
# -> 201 {"amount": 100, "status": "RESERVED", ...} (JSONB-normalized spacing)
RID=$(curl -s localhost:8080/v1/operations/$RKEY | python3 -c "import sys,json;print(json.load(sys.stdin)['reservationId'])")
CKEY=demo-commit-1
curl -s -X POST localhost:8080/v1/commit -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $CKEY" \
  -d "{\"reservationId\":\"$RID\",\"idempotencyKey\":\"$CKEY\"}"
# -> 200 COMMITTED
curl -s "localhost:8080/v1/query?accountId=$ACCT"
# -> {"accountId":"...","available":900,"committed":100,"reserved":0,"total":1000}
curl -s -X POST localhost:8080/v1/commit -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $CKEY" \
  -d "{\"reservationId\":\"$RID\",\"idempotencyKey\":\"$CKEY\"}"
# -> 200 identical body, no second decrement; same key+different body -> 422
```

If the host already occupies 5432/8080, use overrides (defaults unchanged):

```bash
POSTGRES_PORT=5433 APP_PORT=8081 docker compose -f ../docker-compose.yml up --build
curl -s localhost:8081/health
curl -s localhost:8081/ready
```

## Required toolchain (JDK 25 baseline)

- JDK 25 LTS (Temurin `25.x`). Local env may have JDK 26 — do NOT use it for `main` builds.
- WSL/Ubuntu example:

```bash
ls /usr/lib/jvm/temurin-25-jdk-amd64
export JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
java -version  # 25.x LTS
mvn -v         # Maven 3.6.3+, JDK 25
```

- CI proves JDK 25 (`verify -Pstrict` required green). JDK 26 job is allowed-fail compat lane; no 26-only APIs, no preview APIs (`StructuredTaskScope`) on `main` — explicit `ExecutorService` lifecycle only.
- Verify: `mvn -v` (JDK 25) + `mvn verify -Pstrict` log; ArchUnit test `DomainArchitectureTest` green.
- O1-3 proof: `ReserveCommitSliceIT`, `IdempotencyConcurrentIT`, `OverCapacity409IT`, `KillMidTxIT`
  (all Testcontainers PG16). Concurrency choice: `docs/adr/DEC-LEDGER-04-concurrency.md`.

## Layout

`spec/` `service/` `engines/postgres/` `engines/raft-lab/` (lab flag) `engines/replicated-store/` `control-plane/` (stub) `simulation/` (stub) `docs/adr/`.
