package com.g1do.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.g1do.ledger.infra.JdbcLedgerRepository;
import com.g1do.ledger.service.ExpiryReaper;
import com.g1do.ledger.service.ExpiryService;
import com.g1do.ledger.service.ReserveService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.TransactionSystemException;

/** Real PostgreSQL locks and connection loss exercise the bounded expiry worker. */
@TestPropertySource(
    properties = {
      "ledger.expiry.enabled=false",
      "ledger.expiry.batch-size=2",
      "ledger.expiry.lock-timeout-ms=100"
    })
class ExpiryReaperIT extends PostgresITBase {

  @Autowired private ExpiryService expiry;

  @Autowired private ExpiryReaper reaper;

  @Autowired private ReserveService reserveService;

  @MockitoSpyBean private JdbcLedgerRepository repository;

  @AfterEach
  void clearSpy() {
    reset(repository);
  }

  @Test
  void overlappingSweepsSkipOwnedRowsAndEachExpireExactlyOnce() throws Exception {
    UUID firstAccount = account();
    UUID secondAccount = account();
    UUID first = overdue(firstAccount);
    UUID second = overdue(secondAccount);
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              Object result = invocation.callRealMethod();
              if (((Optional<?>) result).isPresent()) {
                locked.countDown();
                assertThat(finish.await(10, TimeUnit.SECONDS)).isTrue();
              }
              return result;
            })
        .when(repository)
        .lockReservation(first, true);

    try (var pool = Executors.newFixedThreadPool(2)) {
      var one = pool.submit(() -> reaper.sweep());
      try {
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        // A second real sweep runs while the first owns the earlier reservation row.
        assertThat(pool.submit(() -> reaper.sweep()).get(10, TimeUnit.SECONDS)).isEqualTo(1);
      } finally {
        finish.countDown();
      }
      assertThat(one.get(10, TimeUnit.SECONDS)).isEqualTo(1);
    }
    assertExpired(firstAccount, first);
    assertExpired(secondAccount, second);
    assertThat(reaper.sweep()).isZero();
    assertExpired(firstAccount, first);
    assertExpired(secondAccount, second);
  }

  @Test
  void sweepHonorsBatchLimitAndNeverExpiresNullOrFutureDeadlines() {
    UUID account = account();
    UUID first = overdue(account);
    UUID second = overdue(account);
    UUID third = overdue(account);
    UUID forever = reserve(account, null);
    UUID future = reserve(account, Integer.MAX_VALUE);

    assertThat(reaper.sweep()).isEqualTo(2);
    assertThat(expiredCount(account)).isEqualTo(2);
    assertThat(reaper.sweep()).isEqualTo(1);
    assertThat(reaper.sweep()).isZero();
    assertThat(status(first)).isEqualTo("EXPIRED");
    assertThat(status(second)).isEqualTo("EXPIRED");
    assertThat(status(third)).isEqualTo("EXPIRED");
    assertThat(status(forever)).isEqualTo("RESERVED");
    assertThat(status(future)).isEqualTo("RESERVED");
    new InvariantChecker(jdbc, account).assertExpectedCapacity(account, 100, 20, 0);
    assertThat(effects(account, "EXPIRE")).isEqualTo(3);
    assertThat(expiryOutbox(account)).isEqualTo(3);
  }

  @Test
  void busyReservationIsSkippedWithoutLeavingAnOperationClaim() throws Exception {
    UUID busyAccount = account();
    UUID freeAccount = account();
    UUID busy = overdue(busyAccount);
    UUID free = overdue(freeAccount);
    int operationsBefore = expiryOperations();
    try (Connection blocker = appConnection()) {
      lock(blocker, "SELECT 1 FROM reservation WHERE id = ? FOR UPDATE", busy);
      assertThat(reaper.sweep()).isEqualTo(1);
      assertThat(status(busy)).isEqualTo("RESERVED");
      assertExpired(freeAccount, free);
      assertThat(expiryOperations()).isEqualTo(operationsBefore + 1);
      blocker.rollback();
    }
    assertThat(reaper.sweep()).isEqualTo(1);
    assertExpired(busyAccount, busy);
  }

  @Test
  void busyCapacityTimesOutAndWorkerContinuesWithAnotherAccount() throws Exception {
    UUID busyAccount = account();
    UUID freeAccount = account();
    UUID busy = overdue(busyAccount);
    UUID free = overdue(freeAccount);
    int operationsBefore = expiryOperations();
    try (Connection blocker = appConnection()) {
      lock(blocker, "SELECT 1 FROM capacity WHERE account_id = ? FOR UPDATE", busyAccount);
      long started = System.nanoTime();
      assertThat(reaper.sweep()).isEqualTo(1);
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
      assertThat(status(busy)).isEqualTo("RESERVED");
      new InvariantChecker(jdbc, busyAccount).assertExpectedCapacity(busyAccount, 100, 10, 0);
      assertThat(effects(busyAccount, "EXPIRE")).isZero();
      assertThat(expiryOutbox(busyAccount)).isZero();
      assertThat(expiryOperations()).isEqualTo(operationsBefore + 1);
      assertExpired(freeAccount, free);
      blocker.rollback();
    }
    assertThat(reaper.sweep()).isEqualTo(1);
    assertExpired(busyAccount, busy);
  }

  @ParameterizedTest
  @ValueSource(strings = {"commit", "release"})
  void expiryWinningReservationLockRejectsWaitingTerminalCommand(String command) throws Exception {
    UUID account = account();
    UUID reservation = overdue(account);
    String key = "expiry-race-" + UUID.randomUUID();
    try (Connection blocker = appConnection();
        var pool = Executors.newFixedThreadPool(2)) {
      lock(blocker, "SELECT 1 FROM capacity WHERE account_id = ? FOR UPDATE", account);
      // The blocking lazy variant shares the expiry transaction with the reaper; no worker
      // timeout can end it before both contestants are observed waiting inside PostgreSQL.
      var expired = pool.submit(() -> expiry.expire(reservation, false));
      try {
        awaitDatabaseWaiters(1);
        var terminal = pool.submit(() -> command(command, reservation, key));
        try {
          awaitDatabaseWaiters(2);
        } finally {
          blocker.rollback();
        }
        assertThat(expired.get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(terminal.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
      } finally {
        blocker.rollback();
      }
    }
    assertExpired(account, reservation);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM operation WHERE idempotency_key = ?", Integer.class, key))
        .isZero();
    assertThat(reaper.sweep()).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"commit", "release"})
  void commandStartedBeforeDeadlineKeepsTransactionClockAndReaperSkipsItsLock(String command)
      throws Exception {
    UUID account = account();
    UUID reservation = reserve(account, 2);
    String key = "before-deadline-" + UUID.randomUUID();
    try (Connection blocker = appConnection();
        var pool = Executors.newSingleThreadExecutor()) {
      lock(blocker, "SELECT 1 FROM capacity WHERE account_id = ? FOR UPDATE", account);
      var terminal = pool.submit(() -> command(command, reservation, key));
      try {
        awaitDatabaseWaiters(1);
        awaitDeadline(reservation);
        assertThat(reaper.sweep()).isZero();
        blocker.rollback();
        assertThat(terminal.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
      } finally {
        blocker.rollback();
      }
    }
    boolean committed = command.equals("commit");
    assertThat(status(reservation)).isEqualTo(committed ? "COMMITTED" : "RELEASED");
    new InvariantChecker(jdbc, account).assertExpectedCapacity(account, 100, 0, committed ? 10 : 0);
    assertThat(effects(account, "EXPIRE")).isZero();
    assertThat(expiryOutbox(account)).isZero();
    assertThat(reaper.sweep()).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"status", "outbox"})
  void killedExpiryTransactionRollsBackAndSweepRetryCreatesOneEffect(String checkpoint)
      throws Exception {
    UUID account = account();
    UUID reservation = overdue(account);
    int operationsBefore = expiryOperations();
    if (checkpoint.equals("status")) {
      doAnswer(
              invocation -> {
                invocation.callRealMethod();
                killTransaction(reservation);
                return null;
              })
          .when(repository)
          .updateReservationStatus(reservation, "EXPIRED");
    } else {
      doAnswer(
              invocation -> {
                invocation.callRealMethod();
                killTransaction(reservation);
                return null;
              })
          .when(repository)
          .insertOutbox(any(UUID.class), eq("reservation"), any(String.class));
    }
    try {
      assertThatThrownBy(() -> expiry.expire(reservation, true))
          .isInstanceOf(TransactionSystemException.class)
          .satisfies(
              failure -> {
                var transactionFailure = (TransactionSystemException) failure;
                var writeFailure = rootSQLException(transactionFailure.getApplicationException());
                assertThat((Object) writeFailure).isNotNull();
                assertThat(writeFailure.getSQLState()).isEqualTo("57P01");
              });
    } finally {
      reset(repository);
    }
    assertThat(status(reservation)).isEqualTo("RESERVED");
    new InvariantChecker(jdbc, account).assertExpectedCapacity(account, 100, 10, 0);
    assertThat(expiryOperations()).isEqualTo(operationsBefore);
    assertThat(effects(account, "EXPIRE")).isZero();
    assertThat(expiryOutbox(account)).isZero();
    assertThat(reaper.sweep()).isEqualTo(1);
    assertThat(reaper.sweep()).isZero();
    assertExpired(account, reservation);
  }

  private void killTransaction(UUID reservation) throws Exception {
    assertThat(status(reservation)).isEqualTo("EXPIRED");
    int backend = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
    try (var admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var statement = admin.prepareStatement("SELECT pg_terminate_backend(?)")) {
      statement.setInt(1, backend);
      try (var result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        assertThat(result.getBoolean(1)).isTrue();
      }
    }
  }

  private UUID account() {
    UUID account = UUID.randomUUID();
    jdbc.update("INSERT INTO account (id, display_name) VALUES (?, 'expiry-reaper')", account);
    jdbc.update("INSERT INTO capacity (account_id, total) VALUES (?, 100)", account);
    return account;
  }

  private UUID reserve(UUID account, Integer ttl) {
    String key = "expiry-reserve-" + UUID.randomUUID();
    reserveService.reserve(account.toString(), 10, key, ttl, key);
    return jdbc.queryForObject(
        "SELECT r.id FROM reservation r JOIN operation o ON o.id = r.operation_id"
            + " WHERE o.idempotency_key = ?",
        UUID.class,
        key);
  }

  private UUID overdue(UUID account) {
    UUID reservation = reserve(account, 3600);
    jdbc.update(
        "UPDATE reservation SET expires_at = now() - interval '1 second' WHERE id = ?",
        reservation);
    return reservation;
  }

  private String status(UUID reservation) {
    return jdbc.queryForObject(
        "SELECT status FROM reservation WHERE id = ?", String.class, reservation);
  }

  private int expiredCount(UUID account) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM reservation WHERE account_id = ? AND status = 'EXPIRED'",
        Integer.class,
        account);
  }

  private int expiryOperations() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM operation WHERE type = 'EXPIRE'", Integer.class);
  }

  private int effects(UUID account, String kind) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM audit_entry WHERE account_id = ? AND kind = ?",
        Integer.class,
        account,
        kind);
  }

  private int expiryOutbox(UUID account) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM outbox WHERE payload ->> 'accountId' = ?"
            + " AND payload ->> 'status' = 'EXPIRED'",
        Integer.class,
        account.toString());
  }

  private void assertExpired(UUID account, UUID reservation) {
    assertThat(status(reservation)).isEqualTo("EXPIRED");
    new InvariantChecker(jdbc, account).assertExpectedCapacity(account, 100, 0, 0);
    assertThat(
            jdbc.queryForList(
                "SELECT kind FROM audit_entry WHERE account_id = ? ORDER BY id",
                String.class,
                account))
        .containsExactly("RESERVE", "EXPIRE");
    assertThat(expiryOutbox(account)).isEqualTo(1);
    assertThat(
            jdbc.queryForMap(
                "SELECT a.amount, a.before_snapshot ->> 'reserved' AS before_reserved,"
                    + " a.after_snapshot ->> 'reserved' AS after_reserved,"
                    + " a.after_snapshot ->> 'available' AS available, o.type, o.status"
                    + " FROM audit_entry a JOIN operation o ON o.id = a.operation_id"
                    + " WHERE a.account_id = ? AND a.kind = 'EXPIRE'",
                account))
        .containsEntry("amount", 10)
        .containsEntry("before_reserved", "10")
        .containsEntry("after_reserved", "0")
        .containsEntry("available", "100")
        .containsEntry("type", "EXPIRE")
        .containsEntry("status", "COMPLETED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox b JOIN audit_entry a"
                    + " ON b.payload ->> 'operationId' = a.operation_id::text"
                    + " WHERE a.account_id = ? AND a.kind = 'EXPIRE'"
                    + " AND b.aggregate = 'reservation' AND b.dispatched = false"
                    + " AND b.payload ->> 'reservationId' = ?",
                Integer.class,
                account,
                reservation.toString()))
        .isEqualTo(1);
  }

  private MvcResult command(String name, UUID reservation, String key) throws Exception {
    return mockMvc
        .perform(
            post("/v1/" + name)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"reservationId\":\""
                        + reservation
                        + "\",\"idempotencyKey\":\""
                        + key
                        + "\"}"))
        .andReturn();
  }

  private static void lock(Connection connection, String sql, UUID id) throws Exception {
    connection.setAutoCommit(false);
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, id);
      statement.executeQuery().close();
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

  private void awaitDeadline(UUID reservation) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline) {
      if (Boolean.TRUE.equals(
          jdbc.queryForObject(
              "SELECT expires_at <= now() FROM reservation WHERE id = ?",
              Boolean.class,
              reservation))) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("Reservation did not reach its database deadline");
  }
}
