package com.g1do.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * audit_entry is append-only: INSERT/SELECT as app_role succeed, UPDATE/DELETE as app_role are
 * denied (SQLState 42501) and the row is unchanged.
 */
class AuditAppendOnlyIT extends PostgresITBase {

  @Test
  void auditUpdateAndDeleteAsAppRoleAreDenied() throws Exception {
    UUID accountId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    jdbc.update("INSERT INTO account (id, display_name) VALUES (?, ?)", accountId, "audit-test");
    jdbc.update(
        "INSERT INTO operation (id, idempotency_key, type, status, request_hash)"
            + " VALUES (?, ?, 'RESERVE', 'PENDING', 'hash')",
        operationId,
        "audit-key-" + UUID.randomUUID());
    Long auditId =
        jdbc.queryForObject(
            "INSERT INTO audit_entry (operation_id, account_id, kind, amount)"
                + " VALUES (?, ?, 'RESERVE', 10) RETURNING id",
            Long.class,
            operationId,
            accountId);
    assertThat(auditId).isNotNull();

    try (Connection app = appConnection()) {
      SQLException updateFailure = attemptUpdate(app, auditId);
      assertThat((Object) updateFailure).isNotNull();
      assertThat(updateFailure.getSQLState()).isEqualTo("42501");

      SQLException deleteFailure = attemptDelete(app, auditId);
      assertThat((Object) deleteFailure).isNotNull();
      assertThat(deleteFailure.getSQLState()).isEqualTo("42501");
    }

    String kind =
        jdbc.queryForObject("SELECT kind FROM audit_entry WHERE id = ?", String.class, auditId);
    assertThat(kind).isEqualTo("RESERVE");
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_entry WHERE id = ?", Integer.class, auditId);
    assertThat(count).isEqualTo(1);
  }

  private static SQLException attemptUpdate(Connection app, Long auditId) {
    try (PreparedStatement statement =
        app.prepareStatement("UPDATE audit_entry SET kind = 'MUTATED' WHERE id = ?")) {
      statement.setLong(1, auditId);
      statement.executeUpdate();
      fail("UPDATE on audit_entry as app_role must be denied");
      return null;
    } catch (SQLException denied) {
      return denied;
    }
  }

  private static SQLException attemptDelete(Connection app, Long auditId) {
    try (PreparedStatement statement =
        app.prepareStatement("DELETE FROM audit_entry WHERE id = ?")) {
      statement.setLong(1, auditId);
      statement.executeUpdate();
      fail("DELETE on audit_entry as app_role must be denied");
      return null;
    } catch (SQLException denied) {
      return denied;
    }
  }
}
