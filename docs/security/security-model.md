# Security model

The service currently exposes its HTTP endpoints without authentication or authorization. It is
not a multi-tenant API, and callers must not infer account-level access control from the request
parameters.

At the database boundary, the application uses `app_role` and bound JDBC parameters. The Flyway
migration assigns table ownership to `migrator`, grants the application role the DML permissions
needed by the service, and explicitly revokes `UPDATE` and `DELETE` on `audit_entry` from
`app_role`. That makes audit entries append-only for the application role, not for database
owners or superusers.

The configured database credentials are development defaults embedded in the migration and
Compose configuration. Production secret storage, HTTP identity, authorization, encryption, and
incident-response procedures are not implemented in this repository.
