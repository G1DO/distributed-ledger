package com.g1do.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class IdempotencyConcurrentIT extends PostgresITBase {

  @Test
  void fiveConcurrentSameKeySameHashConvergeToOneReservation() throws Exception {
    UUID accountId = UUID.randomUUID();
    jdbc.update("INSERT INTO account (id, display_name) VALUES (?, ?)", accountId, "idem-conc");
    jdbc.update(
        "INSERT INTO capacity (account_id, total, reserved, committed) VALUES (?, 1000, 0, 0)",
        accountId);

    String key = "conc-" + UUID.randomUUID();
    String body =
        "{\"accountId\":\"" + accountId + "\",\"amount\":50,\"idempotencyKey\":\"" + key + "\"}";

    int writers = 5;
    ExecutorService pool = Executors.newFixedThreadPool(writers);
    CountDownLatch ready = new CountDownLatch(writers);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<String>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < writers; i++) {
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  boolean started = start.await(10, TimeUnit.SECONDS);
                  assertThat(started).isTrue();
                  MvcResult result =
                      mockMvc
                          .perform(
                              post("/v1/reserve")
                                  .header("Idempotency-Key", key)
                                  .contentType(MediaType.APPLICATION_JSON)
                                  .content(body))
                          .andReturn();
                  int code = result.getResponse().getStatus();
                  assertThat(code == 200 || code == 201).isTrue();
                  return result.getResponse().getContentAsString();
                }));
      }
      boolean allReady = ready.await(10, TimeUnit.SECONDS);
      assertThat(allReady).isTrue();
      start.countDown();

      List<String> bodies = new ArrayList<>();
      for (Future<String> future : futures) {
        bodies.add(future.get(30, TimeUnit.SECONDS));
      }
      for (String candidate : bodies) {
        assertThat(candidate).isEqualTo(bodies.get(0));
      }
    } finally {
      pool.shutdown();
      boolean terminated = pool.awaitTermination(10, TimeUnit.SECONDS);
      assertThat(terminated).isTrue();
    }

    Integer operations =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM operation WHERE idempotency_key = ?", Integer.class, key);
    assertThat(operations).isEqualTo(1);
  }

  @Test
  void sameKeyDifferentBodyReturns422WithNoStateChange() throws Exception {
    UUID accountId = UUID.randomUUID();
    jdbc.update("INSERT INTO account (id, display_name) VALUES (?, ?)", accountId, "idem-mismatch");
    jdbc.update(
        "INSERT INTO capacity (account_id, total, reserved, committed) VALUES (?, 1000, 0, 0)",
        accountId);

    String key = "mismatch-" + UUID.randomUUID();
    String firstBody =
        "{\"accountId\":\"" + accountId + "\",\"amount\":10,\"idempotencyKey\":\"" + key + "\"}";
    String secondBody =
        "{\"accountId\":\"" + accountId + "\",\"amount\":20,\"idempotencyKey\":\"" + key + "\"}";

    mockMvc
        .perform(
            post("/v1/reserve")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(firstBody))
        .andExpect(status().isCreated());

    mockMvc
        .perform(
            post("/v1/reserve")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(secondBody))
        .andExpect(status().isUnprocessableEntity());

    Integer operations =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM operation WHERE idempotency_key = ?", Integer.class, key);
    assertThat(operations).isEqualTo(1);

    Integer reserved =
        jdbc.queryForObject(
            "SELECT reserved FROM capacity WHERE account_id = ?", Integer.class, accountId);
    assertThat(reserved).isEqualTo(10);
  }
}
