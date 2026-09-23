# Distributed Ledger

Java 25 / Spring Boot 4.1 / PostgreSQL 16 capacity-reservation engine. The implemented slice is
`Reserve → Commit, Release, or Expire → Query`, plus atomic capacity transfers, with transactional idempotency,
audit, and outbox storage. Expiry uses PostgreSQL time, lazy checks, and a single-node scheduled reaper.
Authorization, relay, and PITR are planned, not implemented. This unauthenticated
development service is not production-ready.

## 5-min replay

Run the complete O2 lifecycle and crash/recovery drill from the repository root (local Docker with
Compose, Python 3, and curl required):

```bash
python3 scripts/o2-compose-e2e.py
```

The driver builds the Java 25 image, starts the actual `docker-compose.yml` under a unique
project with disposable volumes and dynamically assigned loopback ports, and exercises
Reserve → Commit, Release, Transfer, scheduled Expire → Query. It checks stored-response
replay, capacity counters, reservation sums, and one audit/outbox effect per operation.
An outbox table lock and an observed database lock wait place a Commit after its business/audit
writes; the driver kills PostgreSQL, restarts it, and checks every business row is unchanged.
It retries that Commit, kills PostgreSQL again after acknowledgment, and verifies byte-identical
replay/lookup after PostgreSQL and application restart. Its own stack and volumes are removed
on completion; existing Compose projects are untouched.

The final line prints the result, elapsed time, and evidence directory under
`service/target/o2-compose-e2e/`: raw HTTP requests/responses, database snapshots, process logs,
environment, base commit, and working-tree patch. A first image build may exceed five minutes.
This automated drill does not establish the later O4 blind-operator recovery SLO.

For a manual Reserve/Commit walkthrough on the normal development stack:

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

From `service/`, `./mvnw -Dit.test=KillMidTxIT,ProcessCrashIT verify -Pstrict` also exercises
injected transaction failures and actual application/PostgreSQL kills on disposable test
infrastructure. The [crash matrix](docs/operations/runbooks/o1-crash-matrix.md) explains those
failure models and their limitations.

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

`reference/kv/` contains the separate deterministic Rust GET/PUT/DELETE/CAS reference.
Its [contract and TLA+ model](docs/design/specifications/kv/README.md) are checked against an
independent Python checker; run `python3 scripts/verify-kv.py` from the repository root.
See [KV verification](docs/development/kv-verification.md) for the pinned tools, 10,000-operation
replay, required merge gate, and bounded verification claims.

## Documentation

The durable technical documentation is indexed in [docs/README.md](docs/README.md): the API
contract, ledger invariants, architecture, development workflow, configuration, security model,
and accepted decisions all live there.
