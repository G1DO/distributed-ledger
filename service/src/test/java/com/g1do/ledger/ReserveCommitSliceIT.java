package com.g1do.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** Full vertical slice: reserve -> query -> commit -> query -> replay, on real Postgres. */
class ReserveCommitSliceIT extends PostgresITBase {

  @Test
  void reserveCommitQuerySliceIsCorrectAndIdempotent() throws Exception {
    UUID accountId = UUID.randomUUID();
    jdbc.update("INSERT INTO account (id, display_name) VALUES (?, ?)", accountId, "slice-test");
    jdbc.update(
        "INSERT INTO capacity (account_id, total, reserved, committed) VALUES (?, 1000, 0, 0)",
        accountId);

    String reserveKey = "reserve-" + UUID.randomUUID();
    String reserveBody =
        "{\"accountId\":\""
            + accountId
            + "\",\"amount\":100,\"idempotencyKey\":\""
            + reserveKey
            + "\"}";

    MvcResult reserveResult =
        mockMvc
            .perform(
                post("/v1/reserve")
                    .header("Idempotency-Key", reserveKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(reserveBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.reservationId").exists())
            .andExpect(jsonPath("$.status").value("RESERVED"))
            .andReturn();
    String reserveResponse = reserveResult.getResponse().getContentAsString();
    String reservationId = extractJsonField(reserveResponse, "reservationId");
    assertThat(reservationId).isNotNull();

    mockMvc
        .perform(get("/v1/query").param("accountId", accountId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reserved").value(100))
        .andExpect(jsonPath("$.available").value(900));

    MvcResult operationsResult =
        mockMvc
            .perform(get("/v1/operations/{key}", reserveKey))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(operationsResult.getResponse().getContentAsString()).isEqualTo(reserveResponse);

    String commitKey = "commit-" + UUID.randomUUID();
    String commitBody =
        "{\"reservationId\":\"" + reservationId + "\",\"idempotencyKey\":\"" + commitKey + "\"}";

    MvcResult commitResult =
        mockMvc
            .perform(
                post("/v1/commit")
                    .header("Idempotency-Key", commitKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(commitBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("COMMITTED"))
            .andReturn();
    String commitResponse = commitResult.getResponse().getContentAsString();

    mockMvc
        .perform(get("/v1/query").param("accountId", accountId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reserved").value(0))
        .andExpect(jsonPath("$.committed").value(100))
        .andExpect(jsonPath("$.available").value(900));

    Integer auditBefore =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_entry WHERE account_id = ?", Integer.class, accountId);

    MvcResult replayResult =
        mockMvc
            .perform(
                post("/v1/commit")
                    .header("Idempotency-Key", commitKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(commitBody))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(replayResult.getResponse().getContentAsString()).isEqualTo(commitResponse);

    Integer auditAfter =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_entry WHERE account_id = ?", Integer.class, accountId);
    assertThat(auditAfter).isEqualTo(auditBefore);

    mockMvc
        .perform(get("/v1/query").param("accountId", accountId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.committed").value(100));
  }

  private static String extractJsonField(String json, String field) {
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
    if (matcher.find()) {
      return matcher.group(1);
    }
    return null;
  }
}
