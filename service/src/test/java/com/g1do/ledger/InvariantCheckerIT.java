package com.g1do.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** Deterministic random history over two accounts, checked after every API operation. */
class InvariantCheckerIT extends PostgresITBase {

  private static final int HISTORY_LENGTH = 80;
  private static final long SEED = 4_210_421L;

  @Test
  void randomReserveCommitReplayAndMismatchHistoriesPreserveI1ThroughI6() throws Exception {
    UUID firstAccount = account("history-first");
    UUID secondAccount = account("history-second");
    List<UUID> accounts = List.of(firstAccount, secondAccount);
    List<Reserve> reservable = new ArrayList<>();
    List<Reserve> allReserves = new ArrayList<>();
    InvariantChecker checker = new InvariantChecker(jdbc);
    Random random = new Random(SEED);

    for (int step = 0; step < HISTORY_LENGTH; step++) {
      int action = random.nextInt(4);
      if (action == 0 || reservable.isEmpty()) {
        Reserve reserve = reserve(accounts.get(random.nextInt(accounts.size())), random, step);
        if (reserve.reservationId() != null) {
          allReserves.add(reserve);
          reservable.add(reserve);
        }
      } else if (action == 1) {
        commit(reservable.remove(random.nextInt(reservable.size())), step);
      } else if (action == 2) {
        replay(allReserves.get(random.nextInt(allReserves.size())));
      } else {
        mismatch(allReserves.get(random.nextInt(allReserves.size())));
      }
      checker.assertO1Invariants();
    }
  }

  @Test
  void reserveForOneAccountLeavesAnotherAccountsCapacityUntouched() throws Exception {
    UUID changed = account("isolation-changed");
    UUID untouched = account("isolation-untouched");
    String key = "isolation-" + UUID.randomUUID();

    mockMvc
        .perform(
            post("/v1/reserve")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(reserveBody(changed, 100, key)))
        .andReturn();

    assertThat(
            jdbc.queryForObject(
                "SELECT available FROM capacity WHERE account_id = ?", Integer.class, untouched))
        .as("I6 another account is unchanged")
        .isEqualTo(1000);
    new InvariantChecker(jdbc).assertO1Invariants();
  }

  private UUID account(String displayName) {
    UUID accountId = UUID.randomUUID();
    jdbc.update("INSERT INTO account (id, display_name) VALUES (?, ?)", accountId, displayName);
    jdbc.update(
        "INSERT INTO capacity (account_id, total, reserved, committed) VALUES (?, 1000, 0, 0)",
        accountId);
    return accountId;
  }

  private Reserve reserve(UUID accountId, Random random, int step) throws Exception {
    String key = "history-reserve-" + step + "-" + UUID.randomUUID();
    int amount = random.nextInt(180) + 1;
    String body = reserveBody(accountId, amount, key);
    MvcResult result =
        mockMvc
            .perform(
                post("/v1/reserve")
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andReturn();
    int status = result.getResponse().getStatus();
    assertThat(status).isIn(201, 409);
    String reservationId =
        status == 201
            ? jsonField(result.getResponse().getContentAsString(), "reservationId")
            : null;
    return new Reserve(accountId, amount, key, body, reservationId);
  }

  private void commit(Reserve reserve, int step) throws Exception {
    String key = "history-commit-" + step + "-" + UUID.randomUUID();
    String body =
        "{\"reservationId\":\""
            + reserve.reservationId()
            + "\",\"idempotencyKey\":\""
            + key
            + "\"}";
    int status =
        mockMvc
            .perform(
                post("/v1/commit")
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andReturn()
            .getResponse()
            .getStatus();
    assertThat(status).isEqualTo(200);
  }

  private void replay(Reserve reserve) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/v1/reserve")
                    .header("Idempotency-Key", reserve.key())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(reserve.body()))
            .andReturn();
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
  }

  private void mismatch(Reserve reserve) throws Exception {
    String changed = reserveBody(reserve.accountId(), reserve.amount() + 1, reserve.key());
    int status =
        mockMvc
            .perform(
                post("/v1/reserve")
                    .header("Idempotency-Key", reserve.key())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(changed))
            .andReturn()
            .getResponse()
            .getStatus();
    assertThat(status).isEqualTo(422);
  }

  private static String reserveBody(UUID accountId, int amount, String key) {
    return "{\"accountId\":\""
        + accountId
        + "\",\"amount\":"
        + amount
        + ",\"idempotencyKey\":\""
        + key
        + "\"}";
  }

  private static String jsonField(String json, String field) {
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
    assertThat(matcher.find()).as("response contains %s", field).isTrue();
    return matcher.group(1);
  }

  private record Reserve(
      UUID accountId, int amount, String key, String body, String reservationId) {}
}
