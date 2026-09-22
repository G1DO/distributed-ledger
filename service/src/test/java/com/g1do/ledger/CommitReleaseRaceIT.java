package com.g1do.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** Force terminal contenders to overlap while the first request holds the reservation lock. */
class CommitReleaseRaceIT extends PostgresITBase {

  @ParameterizedTest
  @ValueSource(strings = {"commit", "release"})
  void commitVersusReleaseHasExactlyOneTerminalEffect(String winner) throws Exception {
    UUID account = reservedAccount();
    UUID reservation = reservation(account);
    String loser = winner.equals("commit") ? "release" : "commit";
    String winnerKey = winner + "-" + UUID.randomUUID();
    String loserKey = loser + "-" + UUID.randomUUID();

    List<MvcResult> results =
        race(
            account,
            () -> request(winner, winnerKey, terminalBody(reservation, winnerKey)),
            () -> request(loser, loserKey, terminalBody(reservation, loserKey)));

    assertThat(results.get(0).getResponse().getStatus()).isEqualTo(200);
    assertThat(results.get(1).getResponse().getStatus()).isEqualTo(409);
    assertTerminalEffect(account, reservation, winner, winnerKey);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM operation WHERE idempotency_key = ?",
                Integer.class,
                loserKey))
        .isZero();
  }

  @Test
  void concurrentDoubleReleaseReplaysTheOriginalBytesWithoutAnotherEffect() throws Exception {
    UUID account = reservedAccount();
    UUID reservation = reservation(account);
    String key = "release-" + UUID.randomUUID();
    String body = terminalBody(reservation, key);

    List<MvcResult> results =
        race(account, () -> request("release", key, body), () -> request("release", key, body));

    assertThat(results.stream().map(result -> result.getResponse().getStatus())).containsOnly(200);
    assertThat(results.get(0).getResponse().getContentAsByteArray())
        .isEqualTo(results.get(1).getResponse().getContentAsByteArray());
    assertTerminalEffect(account, reservation, "release", key);
  }

  @Test
  void concurrentDoubleReleaseWithDifferentKeysRejectsTheSecondEffect() throws Exception {
    UUID account = reservedAccount();
    UUID reservation = reservation(account);
    String firstKey = "release-" + UUID.randomUUID();
    String secondKey = "release-" + UUID.randomUUID();

    List<MvcResult> results =
        race(
            account,
            () -> request("release", firstKey, terminalBody(reservation, firstKey)),
            () -> request("release", secondKey, terminalBody(reservation, secondKey)));

    assertThat(results.get(0).getResponse().getStatus()).isEqualTo(200);
    assertThat(results.get(1).getResponse().getStatus()).isEqualTo(409);
    assertTerminalEffect(account, reservation, "release", firstKey);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM operation WHERE idempotency_key = ?",
                Integer.class,
                secondKey))
        .isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"commit", "release"})
  void sameKeyAndBodyCannotReplayAnotherOperationType(String winner) throws Exception {
    UUID account = reservedAccount();
    UUID reservation = reservation(account);
    String loser = winner.equals("commit") ? "release" : "commit";
    String key = "terminal-" + UUID.randomUUID();
    String body = terminalBody(reservation, key);

    List<MvcResult> results =
        race(account, () -> request(winner, key, body), () -> request(loser, key, body));

    assertThat(results.get(0).getResponse().getStatus()).isEqualTo(200);
    assertThat(results.get(1).getResponse().getStatus()).isEqualTo(422);
    assertTerminalEffect(account, reservation, winner, key);
  }

  private List<MvcResult> race(UUID account, Callable<MvcResult> first, Callable<MvcResult> second)
      throws Exception {
    try (Connection blocker = appConnection();
        var pool = Executors.newFixedThreadPool(2)) {
      blocker.setAutoCommit(false);
      try (PreparedStatement lock =
          blocker.prepareStatement("SELECT 1 FROM capacity WHERE account_id = ? FOR UPDATE")) {
        lock.setObject(1, account);
        lock.executeQuery().close();
      }
      var one = pool.submit(first);
      try {
        // First owns the operation claim and reservation before waiting for capacity.
        awaitDatabaseWaiters(1);
        var two = pool.submit(second);
        try {
          // Second waits for that reservation (different key) or operation claim (same key).
          awaitDatabaseWaiters(2);
        } finally {
          blocker.rollback();
        }
        return List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS));
      } finally {
        blocker.rollback();
      }
    }
  }

  private void awaitDatabaseWaiters(int expected) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    try (Connection observer =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var statement = observer.createStatement()) {
      while (System.nanoTime() < deadline) {
        try (var rows =
            statement.executeQuery(
                "SELECT COUNT(*) FROM pg_stat_activity"
                    + " WHERE usename = 'app_role' AND datname = current_database()"
                    + " AND wait_event_type = 'Lock' AND state = 'active'")) {
          rows.next();
          if (rows.getInt(1) >= expected) {
            return;
          }
        }
        Thread.sleep(20);
      }
    }
    throw new AssertionError("Expected " + expected + " PostgreSQL lock waiters before unlock");
  }

  private UUID reservedAccount() throws Exception {
    UUID account = UUID.randomUUID();
    jdbc.update("INSERT INTO account (id, display_name) VALUES (?, 'terminal-race')", account);
    jdbc.update("INSERT INTO capacity (account_id, total) VALUES (?, 1)", account);
    String key = "reserve-" + UUID.randomUUID();
    String body =
        "{\"accountId\":\"" + account + "\",\"amount\":1,\"idempotencyKey\":\"" + key + "\"}";
    assertThat(request("reserve", key, body).getResponse().getStatus()).isEqualTo(201);
    new InvariantChecker(jdbc, account).assertExpectedCapacity(account, 1, 1, 0);
    return account;
  }

  private UUID reservation(UUID account) {
    return jdbc.queryForObject(
        "SELECT id FROM reservation WHERE account_id = ?", UUID.class, account);
  }

  private MvcResult request(String operation, String key, String body) throws Exception {
    return mockMvc
        .perform(
            post("/v1/" + operation)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andReturn();
  }

  private static String terminalBody(UUID reservation, String key) {
    return "{\"reservationId\":\"" + reservation + "\",\"idempotencyKey\":\"" + key + "\"}";
  }

  private void assertTerminalEffect(UUID account, UUID reservation, String winner, String key) {
    boolean committed = winner.equals("commit");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM reservation WHERE id = ?", String.class, reservation))
        .isEqualTo(committed ? "COMMITTED" : "RELEASED");
    new InvariantChecker(jdbc, account).assertExpectedCapacity(account, 1, 0, committed ? 1 : 0);
    assertThat(
            jdbc.queryForList(
                "SELECT kind FROM audit_entry WHERE account_id = ? ORDER BY id",
                String.class,
                account))
        .containsExactly("RESERVE", winner.toUpperCase(Locale.ROOT));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE payload ->> 'accountId' = ?",
                Integer.class,
                account.toString()))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM operation WHERE idempotency_key = ?"
                    + " AND type = ? AND status = 'COMPLETED'",
                Integer.class,
                key,
                winner.toUpperCase(Locale.ROOT)))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_entry a JOIN operation o"
                    + " ON o.id = a.operation_id WHERE o.idempotency_key = ?",
                Integer.class,
                key))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox b JOIN operation o"
                    + " ON b.payload ->> 'operationId' = o.id::text WHERE o.idempotency_key = ?",
                Integer.class,
                key))
        .isEqualTo(1);
  }
}
