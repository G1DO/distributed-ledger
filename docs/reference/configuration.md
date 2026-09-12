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
