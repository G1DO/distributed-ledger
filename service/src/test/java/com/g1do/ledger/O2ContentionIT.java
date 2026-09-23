package com.g1do.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Gate 1 uses the real HTTP handler and PG16 transactions, without injected transport failures. */
class O2ContentionIT extends PostgresITBase {
  private static final int WRITERS = 100;
  private static final int AVAILABLE = 50;

  @Test
  void exactlyFiftyDistinctOneUnitReservesSucceedAtTheCapacityEdge() throws Exception {
    long seed = Long.getLong("ledger.contention.seed", 160_421L);
    Random random = new Random(seed);
    UUID account =
        new UUID(
            (random.nextLong() & 0xffffffffffff0fffL) | 0x4000L,
            (random.nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L);
    String prefix = "o2-edge-" + account + "-";
    jdbc.update("INSERT INTO account (id, display_name) VALUES (?, 'o2-contention')", account);
    jdbc.update(
        "INSERT INTO capacity (account_id, total, reserved, committed) VALUES (?, ?, 0, 0)",
        account,
        AVAILABLE);

    Path evidence = Path.of("target", "o2-contention", Long.toString(seed));
    Files.createDirectories(evidence);
    Files.writeString(
        evidence.resolve("environment.txt"),
        "seed="
            + seed
            + "\naccount="
            + account
            + "\njava="
            + System.getProperty("java.version")
            + "\npostgres="
            + POSTGRES.getDockerImageName()
            + "\nrepro=./mvnw -Pstrict -Dit.test=O2ContentionIT -Dledger.contention.seed="
            + seed
            + " verify\n");
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < WRITERS; i++) {
      order.add(i);
    }
    Collections.shuffle(order, random);
    CountDownLatch ready = new CountDownLatch(WRITERS);
    CountDownLatch start = new CountDownLatch(1);
    ConcurrentLinkedQueue<Attempt> attempts = new ConcurrentLinkedQueue<>();
    ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    List<Future<?>> futures = new ArrayList<>();
    try {
      for (int writer : order) {
        futures.add(
            pool.submit(
                () -> {
                  String key = prefix + writer;
                  ready.countDown();
                  long began = System.nanoTime();
                  try {
                    if (!start.await(30, TimeUnit.SECONDS)) {
                      throw new IllegalStateException("start barrier timed out");
                    }
                    began = System.nanoTime();
                    var response =
                        mockMvc
                            .perform(
                                post("/v1/reserve")
                                    .header("Idempotency-Key", key)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                        "{\"accountId\":\""
                                            + account
                                            + "\",\"amount\":1,\"idempotencyKey\":\""
                                            + key
                                            + "\"}"))
                            .andReturn()
                            .getResponse();
                    attempts.add(
                        new Attempt(
                            writer,
                            key,
                            began,
                            System.nanoTime() - began,
                            response.getStatus(),
                            response.getContentAsString()));
                  } catch (Exception failure) {
                    // An unknown/error is recorded and fails the gate; it is never counted as 409.
                    attempts.add(
                        new Attempt(
                            writer, key, began, System.nanoTime() - began, 0, failure.toString()));
                  }
                }));
      }
      assertThat(ready.await(30, TimeUnit.SECONDS)).as("all 100 callers at start barrier").isTrue();
      start.countDown();
      for (Future<?> future : futures) {
        future.get(60, TimeUnit.SECONDS);
      }
    } finally {
      start.countDown();
      pool.shutdown();
      boolean stopped = pool.awaitTermination(60, TimeUnit.SECONDS);
      if (!stopped) {
        pool.shutdownNow();
      }
      StringBuilder csv =
          new StringBuilder("seed,writer,key,start_ns,elapsed_ns,http_status,response_or_error\n");
      attempts.stream()
          .sorted(Comparator.comparingInt(Attempt::writer))
          .forEach(
              a ->
                  csv.append(seed)
                      .append(',')
                      .append(a.writer())
                      .append(',')
                      .append(a.key())
                      .append(',')
                      .append(a.start())
                      .append(',')
                      .append(a.elapsed())
                      .append(',')
                      .append(a.status())
                      .append(',')
                      .append('"')
                      .append(a.response().replace("\"", "\"\""))
                      .append('"')
                      .append('\n'));
      Files.writeString(evidence.resolve("attempts.csv"), csv);
      assertThat(stopped)
          .as("all contention workers finished; unresolved work fails the gate")
          .isTrue();
    }

    assertThat(attempts).hasSize(WRITERS);
    assertThat(attempts).allSatisfy(a -> assertThat(a.status()).isIn(201, 409));
    assertThat(attempts.stream().filter(a -> a.status() == 201).count()).isEqualTo(50);
    assertThat(attempts.stream().filter(a -> a.status() == 409).count()).isEqualTo(50);
    var capacity =
        jdbc.queryForMap(
            "SELECT total, reserved, committed, available FROM capacity WHERE account_id = ?",
            account);
    Files.writeString(evidence.resolve("capacity.txt"), capacity + "\n");
    assertThat(capacity)
        .containsEntry("total", 50)
        .containsEntry("reserved", 50)
        .containsEntry("committed", 0)
        .containsEntry("available", 0);
    assertThat(
            jdbc.queryForObject(
                "SELECT SUM(reserved::bigint + committed) FROM capacity WHERE account_id = ?",
                Long.class,
                account))
        .isEqualTo(50);
    assertThat(
            jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM reservation WHERE account_id = ? AND status = 'RESERVED'",
                Long.class,
                account))
        .isEqualTo(50);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM operation WHERE idempotency_key LIKE ?",
                Integer.class,
                prefix + "%"))
        .as("failed reserve claims roll back")
        .isEqualTo(50);
    for (Attempt attempt : attempts) {
      int effects = attempt.status() == 201 ? 1 : 0;
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM operation WHERE idempotency_key = ? AND status = 'COMPLETED'",
                  Integer.class,
                  attempt.key()))
          .isEqualTo(effects);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM audit_entry a JOIN operation o ON o.id = a.operation_id"
                      + " WHERE o.idempotency_key = ?",
                  Integer.class,
                  attempt.key()))
          .isEqualTo(effects);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM outbox b JOIN operation o ON b.payload ->> 'operationId' = o.id::text"
                      + " WHERE o.idempotency_key = ?",
                  Integer.class,
                  attempt.key()))
          .isEqualTo(effects);
    }
  }

  private record Attempt(
      int writer, String key, long start, long elapsed, int status, String response) {}
}
