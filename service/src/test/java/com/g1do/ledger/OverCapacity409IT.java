package com.g1do.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Over-capacity reserve fails with 409 and leaves no partial rows behind. */
class OverCapacity409IT extends PostgresITBase {

  @Test
  void reserveOverCapacityReturns409WithNoRows() throws Exception {
    UUID accountId = UUID.randomUUID();
    jdbc.update("INSERT INTO account (id, display_name) VALUES (?, ?)", accountId, "over-cap");
    jdbc.update(
        "INSERT INTO capacity (account_id, total, reserved, committed) VALUES (?, 100, 0, 0)",
        accountId);

    String key = "over-" + UUID.randomUUID();
    String body =
        "{\"accountId\":\"" + accountId + "\",\"amount\":200,\"idempotencyKey\":\"" + key + "\"}";

    mockMvc
        .perform(
            post("/v1/reserve")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict());

    Integer operations =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM operation WHERE idempotency_key = ?", Integer.class, key);
    assertThat(operations).isEqualTo(0);

    Integer reservations =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM reservation WHERE account_id = ?", Integer.class, accountId);
    assertThat(reservations).isEqualTo(0);

    Integer audits =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_entry WHERE account_id = ?", Integer.class, accountId);
    assertThat(audits).isEqualTo(0);

    mockMvc
        .perform(
            post("/v1/reserve")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict());
  }
}
