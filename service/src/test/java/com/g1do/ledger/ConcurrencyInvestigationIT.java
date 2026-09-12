package com.g1do.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Reproduces unsafe read-modify-write behavior and measures three reserve strategies with 32
 * writers. These strategies are test experiments; the production path remains the documented {@code
 * SELECT ... FOR UPDATE} implementation.
 */
class ConcurrencyInvestigationIT extends PostgresITBase {

  private static final int WRITERS = 32;
  private static final int RETRY_LIMIT = 8;

  @Test
  void unprotectedReadModifyWriteLosesOneOfTwoReservations() throws Exception {
    UUID accountId = account("lost-update", 10);
    CountDownLatch read = new CountDownLatch(2);
    CountDownLatch write = new CountDownLatch(1);

    runWriters(
        2,
        () -> {
          try (Connection connection = appConnection()) {
            connection.setAutoCommit(false);
            int reserved =
                value(connection, "SELECT reserved FROM capacity WHERE account_id = ?", accountId);
            read.countDown();
            assertThat(write.await(10, TimeUnit.SECONDS)).isTrue();
            execute(
                connection,
                "UPDATE capacity SET reserved = ? WHERE account_id = ?",
                reserved + 1,
                accountId);
            connection.commit();
            return Attempt.success(0);
          }
        },
        read,
        write);

    assertThat(value("SELECT reserved FROM capacity WHERE account_id = ?", accountId))
        .as("two successful read-modify-write transactions collapse to one update")
        .isEqualTo(1);
  }

  @Test
  void unprotectedMultiRowPredicateAllowsWriteSkew() throws Exception {
    UUID first = account("write-skew-first", 100);
    UUID second = account("write-skew-second", 100);
    CountDownLatch read = new CountDownLatch(2);
    CountDownLatch write = new CountDownLatch(1);
    AtomicInteger position = new AtomicInteger();

    runWriters(
        2,
        () -> {
          UUID target = position.getAndIncrement() == 0 ? first : second;
          try (Connection connection = appConnection()) {
            connection.setAutoCommit(false);
            int reserved =
                value(
                    connection,
                    "SELECT COALESCE(SUM(reserved), 0) FROM capacity WHERE account_id IN (?, ?)",
                    first,
                    second);
            assertThat(reserved).isEqualTo(0);
            read.countDown();
            assertThat(write.await(10, TimeUnit.SECONDS)).isTrue();
            execute(connection, "UPDATE capacity SET reserved = 100 WHERE account_id = ?", target);
            connection.commit();
            return Attempt.success(0);
          }
        },
        read,
        write);

    int totalReserved =
        value("SELECT SUM(reserved) FROM capacity WHERE account_id IN (?, ?)", first, second);
    assertThat(totalReserved)
        .as("a cross-row policy of one 100-unit reservation is violated without predicate locking")
        .isEqualTo(200);
  }

  @Test
  void thirtyTwoWriterBenchmarkKeepsCapacityValidForAllStrategies() throws Exception {
    BenchmarkResult pessimistic = benchmark("pessimistic", this::pessimisticReserve);
    BenchmarkResult optimistic = benchmark("optimistic", this::optimisticReserve);
    BenchmarkResult serializable = benchmark("serializable", this::serializableReserve);

    assertThat(pessimistic.successes()).isEqualTo(WRITERS);
    assertThat(serializable.successes()).isEqualTo(WRITERS);
    assertThat(optimistic.successes()).isBetween(1, WRITERS);
    assertThat(pessimistic.deadlocks()).isZero();
    assertThat(serializable.deadlocks()).isZero();
  }

  private BenchmarkResult benchmark(String strategy, ReserveAttempt reserve) throws Exception {
    UUID accountId = account("benchmark-" + strategy, 10_000);
    CountDownLatch ready = new CountDownLatch(WRITERS);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(WRITERS);
    List<Future<Attempt>> futures = new ArrayList<>();
    try {
      for (int writer = 0; writer < WRITERS; writer++) {
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                  long started = System.nanoTime();
                  Attempt attempt = reserve.run(accountId);
                  return attempt.withElapsedMicros((System.nanoTime() - started) / 1_000);
                }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      List<Attempt> attempts = new ArrayList<>();
      for (Future<Attempt> future : futures) {
        attempts.add(future.get(30, TimeUnit.SECONDS));
      }
      BenchmarkResult result = BenchmarkResult.from(strategy, attempts);
      System.out.printf(
          "O1_BENCH,%s,%d,%d,%d,%d,%d,%d,%d%n",
          result.strategy(),
          result.successes(),
          result.conflicts(),
          result.serializationFailures(),
          result.deadlocks(),
          result.p50(),
          result.p95(),
          result.p99());
      return result;
    } finally {
      pool.shutdown();
      assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private Attempt pessimisticReserve(UUID accountId) throws Exception {
    try (Connection connection = appConnection()) {
      connection.setAutoCommit(false);
      int available =
          value(
              connection,
              "SELECT available FROM capacity WHERE account_id = ? FOR UPDATE",
              accountId);
      if (available < 1) {
        connection.rollback();
        return Attempt.conflict(0);
      }
      execute(
          connection,
          "UPDATE capacity SET reserved = reserved + 1 WHERE account_id = ?",
          accountId);
      connection.commit();
      return Attempt.success(0);
    }
  }

  private Attempt optimisticReserve(UUID accountId) throws Exception {
    try (Connection connection = appConnection()) {
      connection.setAutoCommit(false);
      int version =
          value(connection, "SELECT version FROM capacity WHERE account_id = ?", accountId);
      int updated =
          update(
              connection,
              "UPDATE capacity SET reserved = reserved + 1, version = version + 1"
                  + " WHERE account_id = ? AND available >= 1 AND version = ?",
              accountId,
              version);
      connection.commit();
      return updated == 1 ? Attempt.success(0) : Attempt.conflict(0);
    }
  }

  private Attempt serializableReserve(UUID accountId) throws Exception {
    for (int retry = 0; retry <= RETRY_LIMIT; retry++) {
      try (Connection connection = appConnection()) {
        connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        connection.setAutoCommit(false);
        int available =
            value(connection, "SELECT available FROM capacity WHERE account_id = ?", accountId);
        if (available < 1) {
          connection.rollback();
          return Attempt.conflict(retry);
        }
        execute(
            connection,
            "UPDATE capacity SET reserved = reserved + 1 WHERE account_id = ?",
            accountId);
        connection.commit();
        return Attempt.success(retry);
      } catch (SQLException failure) {
        if ("40001".equals(failure.getSQLState()) && retry < RETRY_LIMIT) {
          continue;
        }
        return Attempt.failure(retry, failure.getSQLState());
      }
    }
    throw new IllegalStateException("unreachable retry loop");
  }

  private UUID account(String displayName, int total) {
    UUID accountId = UUID.randomUUID();
    jdbc.update("INSERT INTO account (id, display_name) VALUES (?, ?)", accountId, displayName);
    jdbc.update(
        "INSERT INTO capacity (account_id, total, reserved, committed) VALUES (?, ?, 0, 0)",
        accountId,
        total);
    return accountId;
  }

  private void runWriters(
      int writers, Callable<Attempt> callable, CountDownLatch read, CountDownLatch write)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(writers);
    try {
      List<Future<Attempt>> futures = new ArrayList<>();
      for (int writerNumber = 0; writerNumber < writers; writerNumber++) {
        futures.add(pool.submit(callable));
      }
      assertThat(read.await(10, TimeUnit.SECONDS)).isTrue();
      write.countDown();
      for (Future<Attempt> future : futures) {
        future.get(20, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
      assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private int value(String sql, Object... parameters) {
    return jdbc.queryForObject(sql, Integer.class, parameters);
  }

  private int value(Connection connection, String sql, Object... parameters) throws SQLException {
    try (java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, parameters);
      try (java.sql.ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getInt(1);
      }
    }
  }

  private void execute(Connection connection, String sql, Object... parameters)
      throws SQLException {
    update(connection, sql, parameters);
  }

  private int update(Connection connection, String sql, Object... parameters) throws SQLException {
    try (java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, parameters);
      return statement.executeUpdate();
    }
  }

  private void bind(java.sql.PreparedStatement statement, Object... parameters)
      throws SQLException {
    for (int index = 0; index < parameters.length; index++) {
      statement.setObject(index + 1, parameters[index]);
    }
  }

  @FunctionalInterface
  private interface ReserveAttempt {
    Attempt run(UUID accountId) throws Exception;
  }

  private record Attempt(boolean success, int retries, String sqlState, long elapsedMicros) {
    static Attempt success(int retries) {
      return new Attempt(true, retries, null, 0);
    }

    static Attempt conflict(int retries) {
      return new Attempt(false, retries, "conflict", 0);
    }

    static Attempt failure(int retries, String sqlState) {
      return new Attempt(false, retries, sqlState, 0);
    }

    Attempt withElapsedMicros(long elapsed) {
      return new Attempt(success, retries, sqlState, elapsed);
    }
  }

  private record BenchmarkResult(
      String strategy,
      int successes,
      int conflicts,
      int serializationFailures,
      int deadlocks,
      long p50,
      long p95,
      long p99) {
    static BenchmarkResult from(String strategy, List<Attempt> attempts) {
      List<Long> elapsed = attempts.stream().map(Attempt::elapsedMicros).sorted().toList();
      return new BenchmarkResult(
          strategy,
          (int) attempts.stream().filter(Attempt::success).count(),
          (int) attempts.stream().filter(attempt -> "conflict".equals(attempt.sqlState())).count(),
          attempts.stream().mapToInt(Attempt::retries).sum(),
          (int) attempts.stream().filter(attempt -> "40P01".equals(attempt.sqlState())).count(),
          percentile(elapsed, 0.50),
          percentile(elapsed, 0.95),
          percentile(elapsed, 0.99));
    }

    private static long percentile(List<Long> values, double percentile) {
      int index = Math.max(0, (int) Math.ceil(values.size() * percentile) - 1);
      return values.get(index);
    }
  }
}
