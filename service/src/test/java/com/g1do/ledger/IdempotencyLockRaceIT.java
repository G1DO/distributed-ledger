package com.g1do.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** Force both requests to wait inside PostgreSQL before allowing the winning write to finish. */
class IdempotencyLockRaceIT extends PostgresITBase {

  @Test
  void concurrentReplayOfLastCapacityReturnsOriginalBody() throws Exception {
    UUID account = account();
    String key = "edge-" + UUID.randomUUID();
    String body = reserveBody(account, key, 1);
    List<MvcResult> results =
        race(
            "SELECT 1 FROM capacity WHERE account_id = ? FOR UPDATE",
            account,
            () -> request("reserve", key, body),
            () -> request("reserve", key, body));
    assertThat(results.stream().map(r -> r.getResponse().getStatus()))
        .containsExactlyInAnyOrder(201, 200);
    assertThat(results.get(0).getResponse().getContentAsString())
        .isEqualTo(results.get(1).getResponse().getContentAsString());
    assertThat(
            jdbc.queryForObject(
                "SELECT reserved FROM capacity WHERE account_id = ?", Integer.class, account))
        .isEqualTo(1);
    assertOneOperation(key);
  }

  @Test
  void concurrentCommitReplayDoesNotRejectTheTerminalState() throws Exception {
    UUID account = account();
    String reserveKey = "seed-" + UUID.randomUUID();
    assertThat(
            request("reserve", reserveKey, reserveBody(account, reserveKey, 1))
                .getResponse()
                .getStatus())
        .isEqualTo(201);
    UUID reservation =
        jdbc.queryForObject("SELECT id FROM reservation WHERE account_id = ?", UUID.class, account);
    String key = "commit-" + UUID.randomUUID();
    String body = "{\"reservationId\":\"" + reservation + "\",\"idempotencyKey\":\"" + key + "\"}";
    List<MvcResult> results =
        race(
            "SELECT 1 FROM reservation WHERE id = ? FOR UPDATE",
            reservation,
            () -> request("commit", key, body),
            () -> request("commit", key, body));
    assertThat(results.stream().map(r -> r.getResponse().getStatus())).containsOnly(200);
    assertThat(results.get(0).getResponse().getContentAsString())
        .isEqualTo(results.get(1).getResponse().getContentAsString());
    assertThat(
            jdbc.queryForObject(
                "SELECT committed FROM capacity WHERE account_id = ?", Integer.class, account))
        .isEqualTo(1);
    assertOneOperation(key);
  }

  @Test
  void rolledBackOwnerReleasesClaimToWaitingRequest() throws Exception {
    UUID account = account();
    String key = "rollback-" + UUID.randomUUID();
    try (Connection blocker = appConnection();
        var pool = Executors.newFixedThreadPool(2)) {
      blocker.setAutoCommit(false);
      try (PreparedStatement lock =
          blocker.prepareStatement("SELECT 1 FROM capacity WHERE account_id = ? FOR UPDATE")) {
        lock.setObject(1, account);
        lock.executeQuery().close();
      }
      var rejected = pool.submit(() -> request("reserve", key, reserveBody(account, key, 2)));
      try {
        // The oversized request owns the key before the viable request starts.
        awaitDatabaseWaiters(1);
        var accepted = pool.submit(() -> request("reserve", key, reserveBody(account, key, 1)));
        try {
          awaitDatabaseWaiters(2);
        } finally {
          blocker.rollback();
        }
        assertThat(rejected.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
        assertThat(accepted.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
      } finally {
        blocker.rollback();
      }
    }
    assertOneOperation(key);
    new InvariantChecker(jdbc, account).assertExpectedCapacity(account, 1, 1, 0);
  }

  @Test
  void concurrentDifferentBodyIsMismatchEvenWhenWinnerExhaustsCapacity() throws Exception {
    UUID account = account();
    String key = "mismatch-" + UUID.randomUUID();
    List<MvcResult> results =
        race(
            "SELECT 1 FROM capacity WHERE account_id = ? FOR UPDATE",
            account,
            () -> request("reserve", key, reserveBody(account, key, 1)),
            () ->
                request(
                    "reserve",
                    key,
                    reserveBody(account, key, 1).replace("}", ",\"ttlSec\":3600}")));
    assertThat(results.stream().map(r -> r.getResponse().getStatus()))
        .containsExactlyInAnyOrder(201, 422);
    assertOneOperation(key);
  }

  private List<MvcResult> race(
      String lockSql, UUID id, Callable<MvcResult> first, Callable<MvcResult> second)
      throws Exception {
    try (Connection blocker = appConnection();
        var pool = Executors.newFixedThreadPool(2)) {
      blocker.setAutoCommit(false);
      try (PreparedStatement lock = blocker.prepareStatement(lockSql)) {
        lock.setObject(1, id);
        lock.executeQuery().close();
      }
      var one = pool.submit(first);
      var two = pool.submit(second);
      try {
        awaitDatabaseWaiters(2);
      } finally {
        blocker.rollback();
      }
      return List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS));
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

  private UUID account() {
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO account (id, display_name) VALUES (?, 'lock-race')", id);
    jdbc.update("INSERT INTO capacity (account_id, total) VALUES (?, 1)", id);
    return id;
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

  private static String reserveBody(UUID account, String key, int amount) {
    return "{\"accountId\":\""
        + account
        + "\",\"amount\":"
        + amount
        + ",\"idempotencyKey\":\""
        + key
        + "\"}";
  }

  private void assertOneOperation(String key) {
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM operation WHERE idempotency_key = ?", Integer.class, key))
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
