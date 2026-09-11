package com.g1do.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** DB-level proof that over-capacity states and non-positive amounts are impossible. */
class CapacityCheckIT extends PostgresITBase {

  @Test
  void overCapacityUpdateFailsViaCheckAndStateUnchanged() {
    UUID accountId = UUID.randomUUID();
    jdbc.update("INSERT INTO account (id, display_name) VALUES (?, ?)", accountId, "cap-test");
    jdbc.update(
        "INSERT INTO capacity (account_id, total, reserved, committed) VALUES (?, 100, 0, 0)",
        accountId);

    assertThatThrownBy(
            () -> jdbc.update("UPDATE capacity SET reserved = 200 WHERE account_id = ?", accountId))
        .isInstanceOf(DataIntegrityViolationException.class)
        .satisfies(
            failure -> {
              SQLException root = rootSQLException(failure);
              assertThat((Object) root).isNotNull();
              assertThat(root.getSQLState()).isEqualTo("23514");
            });

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT total, reserved, committed, available FROM capacity WHERE account_id = ?",
            accountId);
    assertThat(((Number) row.get("total")).intValue()).isEqualTo(100);
    assertThat(((Number) row.get("reserved")).intValue()).isEqualTo(0);
    assertThat(((Number) row.get("available")).intValue()).isEqualTo(100);
  }

  @Test
  void nonPositiveReservationAmountRejected() {
    UUID accountId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    jdbc.update("INSERT INTO account (id, display_name) VALUES (?, ?)", accountId, "amount-test");
    jdbc.update(
        "INSERT INTO capacity (account_id, total, reserved, committed) VALUES (?, 100, 0, 0)",
        accountId);
    jdbc.update(
        "INSERT INTO operation (id, idempotency_key, type, status, request_hash)"
            + " VALUES (?, ?, 'RESERVE', 'PENDING', 'hash')",
        operationId,
        "amount-key-" + UUID.randomUUID());

    for (int badAmount : new int[] {0, -5}) {
      UUID reservationId = UUID.randomUUID();
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "INSERT INTO reservation (id, account_id, operation_id, amount, status)"
                          + " VALUES (?, ?, ?, ?, 'RESERVED')",
                      reservationId,
                      accountId,
                      operationId,
                      badAmount))
          .isInstanceOf(DataIntegrityViolationException.class)
          .satisfies(
              failure -> {
                SQLException root = rootSQLException(failure);
                assertThat((Object) root).isNotNull();
                assertThat(root.getSQLState()).isEqualTo("23514");
              });
    }

    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM reservation WHERE account_id = ?", Integer.class, accountId);
    assertThat(count).isEqualTo(0);
  }
}
