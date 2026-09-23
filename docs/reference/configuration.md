# Configuration reference

The application listens on port `8080` by default and connects to PostgreSQL through the values
below. The Docker Compose defaults are for local development only.

| Variable | Default | Purpose |
| --- | --- | --- |
| `POSTGRES_HOST` | `localhost` (`postgres` in Compose) | Database host |
| `POSTGRES_DB` | `ledger` | Database name |
| `POSTGRES_USER` | `ledger` | Fallback database/Flyway user |
| `POSTGRES_PASSWORD` | `ledger` | Fallback database/Flyway password |
| `APP_DB_USER` | value of `POSTGRES_USER` | Application datasource user |
| `APP_DB_PASSWORD` | value of `POSTGRES_PASSWORD` | Application datasource password |
| `FLYWAY_USER` | value of `POSTGRES_USER` | Flyway user |
| `FLYWAY_PASSWORD` | value of `POSTGRES_PASSWORD` | Flyway password |
| `POSTGRES_PORT` | `5432` | Host port published by Compose |
| `APP_PORT` | `8080` | Host port published by Compose |

`docker-compose.yml` overrides the app connection to `app_role` / `app` and keeps Flyway on
`ledger` / `ledger`. The V1 migration embeds local-only role passwords; do not treat these values
as a production secret-management solution.

## Expiry reaper

| Property | Environment variable | Default | Purpose |
| --- | --- | --- | --- |
| `ledger.expiry.enabled` | `LEDGER_EXPIRY_ENABLED` | `true` | Enables the single-node background scheduler |
| `ledger.expiry.interval-ms` | `LEDGER_EXPIRY_INTERVAL_MS` | `1000` | Fixed delay in milliseconds between completed sweeps |
| `ledger.expiry.batch-size` | `LEDGER_EXPIRY_BATCH_SIZE` | `100` | Maximum candidates examined per scheduled sweep |
| `ledger.expiry.lock-timeout-ms` | `LEDGER_EXPIRY_LOCK_TIMEOUT_MS` | `100` | PostgreSQL lock timeout in milliseconds for each background expiry transaction |

Interval, batch size, and lock timeout must be positive. Each candidate has its own short
transaction; locked reservations are skipped and capacity-lock timeouts roll back that item for
a later sweep. The background timeout does not change user transaction lock timeouts.
Run the scheduler on one application node only; there is no distributed scheduler lock.

Set `LEDGER_EXPIRY_ENABLED=false` and restart to stop background sweeps. Lazy expiry on capacity
queries, Commit, and Release remains active. See the [expiry runbook](../operations/runbooks/o2-expiry-reaper.md)
for rollout, recovery, and rollback.

## Verification controls

These are test JVM properties, passed to Maven with `-D`; they do not change runtime behavior.

| Property | Default | Purpose |
| --- | --- | --- |
| `ledger.contention.seed` | `160421` | Reproduce capacity-edge account, keys and caller submission order |
| `ledger.history.seed` | `4210421` | First generated-history seed; subsequent histories increment it |
| `ledger.history.count` | `1000` | Corpus size; smaller values are debugging runs, not the O2 exit gate |
| `ledger.history.repro` | unset | Replay a saved symbolic `.history` file instead of generating a corpus |
| `ledger.history.shrinkBudget` | `100` | Nonnegative maximum same-failure replay attempts for deletion shrinking |

The Compose verification driver needs Python 3 and curl and uses a fresh project with Docker-assigned
loopback ports. It ignores local Compose project/port overrides and writes evidence under
`service/target/o2-compose-e2e/`. See [testing](../development/testing.md) and the
[system verification runbook](../operations/runbooks/o2-system-verification.md).
