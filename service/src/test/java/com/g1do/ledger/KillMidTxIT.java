package com.g1do.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Kill mid-transaction: a rolled-back (crashed) tx leaves no partial drift — sums stay consistent,
 * capacity stays nonnegative, and no orphan reservation survives without its operation.
 */
class KillMidTxIT extends PostgresITBase {

  @Test
  void rolledBackTxLeavesNoPartialDrift() throws Exception {
    UUID accountId = UUID.randomUUID();
    jdbc.update("INSERT INTO account (id, display_name) VALUES (?, ?)", accountId, "kill-test");
    jdbc.update(
        "INSERT INTO capacity (account_id, total, reserved, committed) VALUES (?, 500, 0, 0)",
        accountId);

    UUID operationId = UUID.randomUUID();
    UUID reservationId = UUID.randomUUID();
    try (Connection connection = appConnection()) {
      connection.setAutoCommit(false);
      try (PreparedStatement operation =
          connection.prepareStatement(
              "INSERT INTO operation (id, idempotency_key, type, status, request_hash)"
                  + " VALUES (?, ?, 'RESERVE', 'PENDING', 'hash-crash')")) {
        operation.setObject(1, operationId);
        operation.setString(2, "crash-" + UUID.randomUUID());
        operation.executeUpdate();
      }
      try (PreparedStatement reservation =
          connection.prepareStatement(
              "INSERT INTO reservation (id, account_id, operation_id, amount, status)"
                  + " VALUES (?, ?, ?, ?, 'RESERVED')")) {
        reservation.setObject(1, reservationId);
        reservation.setObject(2, accountId);
        reservation.setObject(3, operationId);
        reservation.setInt(4, 100);
        reservation.executeUpdate();
      }
      connection.rollback();
    }

    Map<String, Object> capacity =
        jdbc.queryForMap(
            "SELECT total, reserved, committed, available FROM capacity WHERE account_id = ?",
            accountId);
    assertThat(((Number) capacity.get("total")).intValue()).isEqualTo(500);
    assertThat(((Number) capacity.get("reserved")).intValue()).isEqualTo(0);
    assertThat(((Number) capacity.get("committed")).intValue()).isEqualTo(0);
    assertThat(((Number) capacity.get("available")).intValue()).isEqualTo(500);

    Integer orphans =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM reservation r LEFT JOIN operation o ON r.operation_id = o.id"
                + " WHERE o.id IS NULL",
            Integer.class);
    assertThat(orphans).isEqualTo(0);

    Integer crashedReservations =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM reservation WHERE id = ?", Integer.class, reservationId);
    assertThat(crashedReservations).isEqualTo(0);

    String key = "after-crash-" + UUID.randomUUID();
    String body =
        "{\"accountId\":\"" + accountId + "\",\"amount\":100,\"idempotencyKey\":\"" + key + "\"}";
    mockMvc
        .perform(
            post("/v1/reserve")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated());

    Map<String, Object> after =
        jdbc.queryForMap(
            "SELECT total, reserved, committed, available FROM capacity WHERE account_id = ?",
            accountId);
    int reserved = ((Number) after.get("reserved")).intValue();
    int committed = ((Number) after.get("committed")).intValue();
    int total = ((Number) after.get("total")).intValue();
    int available = ((Number) after.get("available")).intValue();
    assertThat(reserved + committed).isEqualTo(100);
    assertThat(total - reserved - committed).isEqualTo(available);
    assertThat(available).isGreaterThanOrEqualTo(0);
  }
}
