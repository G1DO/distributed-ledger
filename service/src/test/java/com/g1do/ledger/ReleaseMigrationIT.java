package com.g1do.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class ReleaseMigrationIT {

  @Test
  void upgradePreservesLegacyDataAndDatabaseGuards() throws Exception {
    String schema = "release_migration_" + UUID.randomUUID().toString().replace("-", "");
    var postgres = PostgresITBase.POSTGRES;
    try (Connection connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
      try {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .defaultSchema(schema)
            .schemas(schema)
            .target("1")
            .load()
            .migrate();
        connection.setSchema(schema);
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        // V1 grants usage on public only. The table owner also needs usage on this test schema
        // when PostgreSQL evaluates the stored generated capacity column.
        jdbc.execute("GRANT USAGE ON SCHEMA " + schema + " TO migrator");
        UUID accountId = UUID.randomUUID();
        jdbc.update("INSERT INTO account VALUES (?, 'legacy-account')", accountId);
        jdbc.update(
            "INSERT INTO capacity (account_id, total, reserved, committed, version)"
                + " VALUES (?, 20, 5, 3, 7)",
            accountId);
        for (String status : List.of("RESERVED", "COMMITTED")) {
          UUID operationId = UUID.randomUUID();
          jdbc.update(
              "INSERT INTO operation"
                  + " (id, idempotency_key, type, status, request_hash, response_body)"
                  + " VALUES (?, ?, 'RESERVE', 'COMPLETED', 'legacy-hash', '{\"legacy\":true}')",
              operationId,
              "legacy-" + status);
          jdbc.update(
              "INSERT INTO reservation"
                  + " (id, account_id, operation_id, amount, status, expires_at)"
                  + " VALUES (?, ?, ?, ?, ?, CASE WHEN ? = 'COMMITTED'"
                  + " THEN TIMESTAMPTZ '2026-01-01 12:00:00+00' ELSE NULL END)",
              UUID.randomUUID(),
              accountId,
              operationId,
              "RESERVED".equals(status) ? 5 : 3,
              status,
              status);
          jdbc.update(
              "INSERT INTO audit_entry (operation_id, account_id, kind, amount)"
                  + " VALUES (?, ?, 'RESERVE', ?)",
              operationId,
              accountId,
              "RESERVED".equals(status) ? 5 : 3);
          jdbc.update(
              "INSERT INTO outbox (id, aggregate, payload)"
                  + " VALUES (?, 'RESERVATION', '{\"legacy\":true}')",
              UUID.randomUUID());
        }

        Map<String, List<Map<String, Object>>> original = new LinkedHashMap<>();
        for (String table :
            List.of("account", "capacity", "operation", "reservation", "audit_entry", "outbox")) {
          original.put(table, jdbc.queryForList("SELECT * FROM " + table));
        }

        var migrated =
            Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .defaultSchema(schema)
                .schemas(schema)
                .load()
                .migrate();
        assertThat(migrated.migrationsExecuted).isEqualTo(1);
        original.forEach(
            (table, rows) ->
                assertThat(jdbc.queryForList("SELECT * FROM " + table))
                    .as("preserved %s rows", table)
                    .containsExactlyInAnyOrderElementsOf(rows));

        for (String status : List.of("RELEASED", "EXPIRED")) {
          assertThat(
                  jdbc.update(
                      "INSERT INTO reservation"
                          + " (id, account_id, operation_id, amount, status)"
                          + " SELECT ?, account_id, operation_id, amount, ? FROM reservation"
                          + " WHERE status = 'RESERVED'",
                      UUID.randomUUID(),
                      status))
              .isEqualTo(1);
        }
        assertCheckViolation(jdbc, "UPDATE reservation SET status = 'UNKNOWN'");
        assertCheckViolation(jdbc, "UPDATE capacity SET reserved = total + 1");
        assertThat(jdbc.queryForList("SELECT * FROM capacity"))
            .containsExactlyInAnyOrderElementsOf(original.get("capacity"));

        // V1 grants schema access specifically to public; inspect table privileges directly.
        for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
          assertThat(
                  jdbc.queryForObject(
                      "SELECT has_table_privilege('app_role', ?, ?)",
                      Boolean.class,
                      schema + ".audit_entry",
                      privilege))
              .as("audit %s privilege", privilege)
              .isEqualTo(List.of("SELECT", "INSERT").contains(privilege));
        }
      } finally {
        try (Statement statement = connection.createStatement()) {
          statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
      }
    }
  }

  private static void assertCheckViolation(JdbcTemplate jdbc, String sql) {
    assertThatThrownBy(() -> jdbc.update(sql))
        .isInstanceOf(DataIntegrityViolationException.class)
        .satisfies(
            failure -> {
              SQLException root = PostgresITBase.rootSQLException(failure);
              assertThat((Object) root).isNotNull();
              assertThat(root.getSQLState()).isEqualTo("23514");
            });
  }
}
