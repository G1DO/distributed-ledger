# Distributed Ledger

Java 25 / Spring Boot 4.1 / PostgreSQL 16 capacity-reservation engine. The implemented slice is
`Reserve → Commit → Query`, with transactional idempotency, audit, and outbox storage.
O1 verification is in progress; Release, Transfer, expiry, authorization, relay, and PITR are
planned, not implemented. This unauthenticated development service is not production-ready.

## 5-min replay

```bash
cd service
./mvnw verify -Pstrict
docker compose -f ../docker-compose.yml up --build -d
curl --fail --retry 20 --retry-delay 1 --retry-connrefused -sS localhost:8080/health
curl -s localhost:8080/ready
```

Expected: `{"status":"UP"}` on both endpoints; app + Postgres 16 running.

Seed one account (no account-create endpoint in O1 — single principal):

```bash
docker compose -f ../docker-compose.yml exec postgres psql -U ledger -d ledger -c \
  "INSERT INTO account (id, display_name) VALUES ('11111111-1111-4111-8111-111111111111','demo')"
docker compose -f ../docker-compose.yml exec postgres psql -U ledger -d ledger -c \
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

Crash/restart replay: start a reserve and stop the app before its response. A request can either
commit before the stop or roll back with the lost connection; it must never leave a partial state.
After restart, reusing the same key either returns the committed body or creates the one valid
reserve.

```bash
KKEY=kill-reserve-1
curl -sS -X POST localhost:8080/v1/reserve -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KKEY" \
  -d "{\"accountId\":\"$ACCT\",\"amount\":1,\"idempotencyKey\":\"$KKEY\"}" &
sleep 0.05; docker compose -f ../docker-compose.yml kill app; wait || true
docker compose -f ../docker-compose.yml up -d app
curl -s -X POST localhost:8080/v1/reserve -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KKEY" \
  -d "{\"accountId\":\"$ACCT\",\"amount\":1,\"idempotencyKey\":\"$KKEY\"}"
curl -s localhost:8080/v1/operations/$KKEY
curl -s "localhost:8080/v1/query?accountId=$ACCT"
```

The timing smoke test above intentionally accepts either commit outcome. From `service/`, use
`./mvnw -Dit.test=KillMidTxIT,ProcessCrashIT verify -Pstrict` for injected transaction failures
and actual application/PostgreSQL kills on disposable test infrastructure. The [O1 docs](docs/README.md)
separate each failure model and its limitations from the 32-writer concurrency experiment.

If the host already occupies 5432/8080, use overrides (defaults unchanged):

```bash
POSTGRES_PORT=5433 APP_PORT=8081 docker compose -f ../docker-compose.yml up --build -d
curl --fail --retry 20 --retry-delay 1 --retry-connrefused -sS localhost:8081/health
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
./mvnw -v      # from service/: pinned Maven 3.9.11, JDK 25
```

- CI proves JDK 25 (`verify -Pstrict` required green). JDK 26 job is allowed-fail compat lane; no 26-only APIs, no preview APIs (`StructuredTaskScope`) on `main` — explicit `ExecutorService` lifecycle only.
- Verify from `service/`: `./mvnw -v` (JDK 25) + `./mvnw clean verify -Pstrict`;
  Java 26 requires `-Pstrict,compat26` and is not the release baseline.
- O1-3 proof: `ReserveCommitSliceIT`, `IdempotencyConcurrentIT`, `OverCapacity409IT`, `KillMidTxIT`
  (all Testcontainers PG16). Concurrency choice: [DEC-LEDGER-04](docs/decisions/DEC-LEDGER-04-concurrency.md).

## Layout

`service/` `engines/` `control-plane/` (no implemented API) `simulation/` (no implemented harness) `docs/`.

## Documentation

The durable technical documentation is indexed in [docs/README.md](docs/README.md): the API
contract, ledger invariants, architecture, development workflow, configuration, security model,
and accepted decisions all live there.
