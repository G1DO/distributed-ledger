# Distributed Ledger

Skeleton for O1 (`O1-1`): spec-first + buildable Spring Boot app + green CI. No business logic yet.

## 5-min replay (stub)

```bash
cd service
mvn verify -Pstrict
docker compose -f ../docker-compose.yml up --build
curl -s localhost:8080/health
curl -s localhost:8080/ready
```

Expected: `{"status":"UP"}` on both endpoints; app + Postgres 16 running.

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

## Layout

`spec/` `service/` `engines/postgres/` `engines/raft-lab/` (lab flag) `engines/replicated-store/` `control-plane/` (stub) `simulation/` (stub) `docs/adr/`.
