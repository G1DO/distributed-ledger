package com.g1do.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/** Deterministic random history over two accounts, checked after every API operation. */
class InvariantCheckerIT extends PostgresITBase {

  private static final int HISTORY_LENGTH = 80;

  @ParameterizedTest(name = "accounting history seed={0}")
  @ValueSource(longs = {4_210_421, 1, 7, 42, 99, 2026, 409, 422})
  void randomHistoriesMatchIndependentAccounting(long seed) throws Exception {
    UUID firstAccount = account("history-first");
    UUID secondAccount = account("history-second");
    List<UUID> accounts = List.of(firstAccount, secondAccount);
    List<Reserve> reservable = new ArrayList<>();
    List<Reserve> allReserves = new ArrayList<>();
    InvariantChecker checker = new InvariantChecker(jdbc, firstAccount, secondAccount);
    Map<UUID, int[]> expected = Map.of(firstAccount, new int[2], secondAccount, new int[2]);
    Random random = new Random(seed);

    for (int step = 0; step < HISTORY_LENGTH; step++) {
      int action = random.nextInt(4);
      if (action == 0 || reservable.isEmpty()) {
        UUID account = accounts.get(random.nextInt(accounts.size()));
        int[] state = expected.get(account);
        Reserve reserve = reserve(account, random, step, 1000 - state[0] - state[1]);
        if (reserve.reservationId() != null) {
          allReserves.add(reserve);
          reservable.add(reserve);
          state[0] += reserve.amount();
        }
      } else if (action == 1) {
        Reserve reserve = reservable.remove(random.nextInt(reservable.size()));
        commit(reserve, step);
        expected.get(reserve.accountId())[0] -= reserve.amount();
        expected.get(reserve.accountId())[1] += reserve.amount();
      } else if (action == 2) {
        replay(allReserves.get(random.nextInt(allReserves.size())));
      } else {
        mismatch(allReserves.get(random.nextInt(allReserves.size())));
      }
      checker.assertO1Invariants();
      for (UUID account : accounts) {
        int[] state = expected.get(account);
        checker.assertExpectedCapacity(account, 1000, state[0], state[1]);
      }
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
        .as("account-scoped mutation: another account is unchanged (not tenant isolation)")
        .isEqualTo(1000);
    new InvariantChecker(jdbc, changed, untouched).assertO1Invariants();
  }

  @Test
  @Transactional
  void checkerRejectsDoubleAccountingEvenWhenGeneratedEquationStillHolds() throws Exception {
    UUID account = account("corrupt-counter");
    reserve(account, new Random(1), 0, 1000);
    jdbc.update("UPDATE capacity SET reserved = reserved + 1 WHERE account_id = ?", account);
    assertThatThrownBy(() -> new InvariantChecker(jdbc, account).assertO1Invariants())
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("reserved matches reservation");
  }

  @Test
  @Transactional
  void checkerRejectsMissingAuditEffect() {
    UUID account = account("missing-audit");
    UUID operation = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO operation (id, idempotency_key, type, status, request_hash, response_body)"
            + " VALUES (?, ?, 'RESERVE', 'COMPLETED', 'fixture', '{}'::jsonb)",
        operation,
        "no-audit-" + UUID.randomUUID());
    jdbc.update(
        "INSERT INTO reservation (id, account_id, operation_id, amount, status)"
            + " VALUES (?, ?, ?, 1, 'RESERVED')",
        UUID.randomUUID(),
        account,
        operation);
    jdbc.update("UPDATE capacity SET reserved = 1 WHERE account_id = ?", account);
    assertThatThrownBy(() -> new InvariantChecker(jdbc, account).assertO1Invariants())
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("reserve audit effects");
  }

  @Test
  @Transactional
  void independentModelRejectsUnexplainedCapacityCreation() {
    UUID account = account("changed-total");
    jdbc.update("UPDATE capacity SET total = total + 1 WHERE account_id = ?", account);
    assertThatThrownBy(
            () -> new InvariantChecker(jdbc, account).assertExpectedCapacity(account, 1000, 0, 0))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("model total");
  }

  private UUID account(String displayName) {
    UUID accountId = UUID.randomUUID();
    jdbc.update("INSERT INTO account (id, display_name) VALUES (?, ?)", accountId, displayName);
    jdbc.update(
        "INSERT INTO capacity (account_id, total, reserved, committed) VALUES (?, 1000, 0, 0)",
        accountId);
    return accountId;
  }

  private Reserve reserve(UUID accountId, Random random, int step, int expectedAvailable)
      throws Exception {
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
    assertThat(status)
        .as("model reserve result at step %s", step)
        .isEqualTo(amount <= expectedAvailable ? 201 : 409);
    String reservationId =
        status == 201
            ? jsonField(result.getResponse().getContentAsString(), "reservationId")
            : null;
    return new Reserve(
        accountId, amount, key, body, reservationId, result.getResponse().getContentAsString());
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
    assertThat(result.getResponse().getContentAsString()).isEqualTo(reserve.response());
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
      UUID accountId, int amount, String key, String body, String reservationId, String response) {}
}
