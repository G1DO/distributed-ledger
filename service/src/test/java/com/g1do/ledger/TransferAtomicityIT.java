package com.g1do.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.g1do.ledger.domain.RequestHash;
import com.g1do.ledger.infra.JdbcLedgerRepository;
import com.g1do.ledger.service.TransferService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.TransactionSystemException;

/** Transfer accounting, real PostgreSQL lock order, concurrent identity, and connection loss. */
class TransferAtomicityIT extends PostgresITBase {

  @Autowired private TransferService transferService;

  @MockitoSpyBean private JdbcLedgerRepository repository;

  @Test
  void transferPreservesReservationsAndStoresBothSnapshotsWithByteIdenticalReplay()
      throws Exception {
    UUID from = account(1000);
    UUID to = account(200);
    UUID untouched = account(99);
    reserve(from, 700, false);
    reserve(from, 200, true);
    reserve(to, 20, false);
    reserve(to, 30, true);
    var reservations = reservations(from, to);
    String key = "transfer-\"\\-" + UUID.randomUUID();

    byte[] response =
        transfer(from, to, 100, key)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fromAccountId").value(from.toString()))
            .andExpect(jsonPath("$.toAccountId").value(to.toString()))
            .andExpect(jsonPath("$.amount").value(100))
            .andExpect(jsonPath("$.idempotencyKey").value(key))
            .andExpect(jsonPath("$.status").value("TRANSFERRED"))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    // Source is exhausted. Canonical hashing ignores JSON field order and whitespace.
    byte[] replay =
        request(
                key,
                "{ \"toAccountId\":\""
                    + to
                    + "\", \"idempotencyKey\":\""
                    + RequestHash.escape(key)
                    + "\", \"amount\":100, \"fromAccountId\":\""
                    + from
                    + "\" }")
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    assertThat(replay).isEqualTo(response);
    assertThat(lookup(key)).isEqualTo(response);
    String canonical =
        "{\"amount\":100,\"fromAccountId\":\""
            + from
            + "\",\"idempotencyKey\":\""
            + RequestHash.escape(key)
            + "\",\"toAccountId\":\""
            + to
            + "\"}";
    assertThat(
            jdbc.queryForObject(
                "SELECT request_hash FROM operation WHERE idempotency_key = ?", String.class, key))
        .isEqualTo(RequestHash.sha256Hex(canonical));
    System.out.printf(
        "Transfer replay evidence: first/replay/lookup byte-identical (%d bytes), key=%s%n",
        response.length, key);
    transfer(from, to, 101, key).andExpect(status().isUnprocessableEntity());
    transfer(UUID.randomUUID(), to, 100, key).andExpect(status().isUnprocessableEntity());
    transfer(from, UUID.randomUUID(), 100, key).andExpect(status().isUnprocessableEntity());
    UUID full = account(Integer.MAX_VALUE);
    transfer(to, full, 1, key).andExpect(status().isUnprocessableEntity());
    mockMvc
        .perform(
            post("/v1/reserve")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"accountId\":\""
                        + from
                        + "\",\"amount\":1,\"idempotencyKey\":\""
                        + RequestHash.escape(key)
                        + "\"}"))
        .andExpect(status().isUnprocessableEntity());

    assertCapacity(from, 900, 700, 200);
    assertCapacity(to, 300, 20, 30);
    assertThat(capacity(from)).containsEntry("version", 4L);
    assertThat(capacity(to)).containsEntry("version", 4L);
    assertCapacity(untouched, 99, 0, 0);
    assertCapacity(full, Integer.MAX_VALUE, 0, 0);
    assertThat(reservations(from, to)).isEqualTo(reservations);
    assertThat(
            jdbc.queryForMap(
                "SELECT account_id, kind, amount FROM audit_entry a JOIN operation o"
                    + " ON o.id = a.operation_id WHERE o.idempotency_key = ?",
                key))
        .containsEntry("account_id", from)
        .containsEntry("kind", "TRANSFER")
        .containsEntry("amount", 100);
    assertThat(
            jdbc.queryForObject(
                "SELECT before_snapshot = CAST(? AS jsonb) AND after_snapshot = CAST(? AS jsonb)"
                    + " FROM audit_entry a JOIN operation o ON o.id = a.operation_id"
                    + " WHERE o.idempotency_key = ?",
                Boolean.class,
                snapshots(from, 1000, 700, 200, to, 200, 20, 30),
                snapshots(from, 900, 700, 200, to, 300, 20, 30),
                key))
        .isTrue();
    assertThat(
            jdbc.queryForMap(
                "SELECT aggregate, dispatched, payload ->> 'fromAccountId' AS from_account,"
                    + " payload ->> 'toAccountId' AS to_account, payload ->> 'amount' AS amount"
                    + " FROM outbox b JOIN operation o ON b.payload ->> 'operationId' = o.id::text"
                    + " WHERE o.idempotency_key = ?",
                key))
        .containsEntry("aggregate", "transfer")
        .containsEntry("dispatched", false)
        .containsEntry("from_account", from.toString())
        .containsEntry("to_account", to.toString())
        .containsEntry("amount", "100");
    assertEffects(key, 1);
    assertSum(from, to, 1200);
  }

  @Test
  void fullIntegerCapacityCanMoveBothWays() throws Exception {
    UUID from = account(Integer.MAX_VALUE);
    UUID to = account(0);
    String firstKey = key();
    transfer(from, to, Integer.MAX_VALUE, firstKey).andExpect(status().isOk());
    assertCapacity(from, 0, 0, 0);
    assertCapacity(to, Integer.MAX_VALUE, 0, 0);
    String secondKey = key();
    transfer(to, from, Integer.MAX_VALUE, secondKey).andExpect(status().isOk());
    assertCapacity(from, Integer.MAX_VALUE, 0, 0);
    assertCapacity(to, 0, 0, 0);
    assertEffects(firstKey, 1);
    assertEffects(secondKey, 1);
    assertSum(from, to, Integer.MAX_VALUE);
  }

  @Test
  void overflowIsBadRequestWithNoPartialStateAndItsClaimCanBeRetried() throws Exception {
    UUID from = account(100);
    UUID full = account(Integer.MAX_VALUE);
    Map<String, Object> fromBefore = capacity(from);
    Map<String, Object> fullBefore = capacity(full);
    String key = key();

    transfer(from, full, 1, key).andExpect(status().isBadRequest());

    assertThat(capacity(from)).isEqualTo(fromBefore);
    assertThat(capacity(full)).isEqualTo(fullBefore);
    assertEffects(key, 0);
    assertSum(from, full, (long) Integer.MAX_VALUE + 100);
    UUID to = account(0);
    transfer(from, to, 1, key).andExpect(status().isOk());
    assertCapacity(from, 99, 0, 0);
    assertCapacity(to, 1, 0, 0);
    assertEffects(key, 1);
  }

  @Test
  void insufficientAvailableAndMissingAccountsLeaveNoEffects() throws Exception {
    UUID from = account(100);
    UUID to = account(0);
    reserve(from, 60, false);
    reserve(from, 40, true);
    Map<String, Object> fromBefore = capacity(from);
    Map<String, Object> toBefore = capacity(to);
    String overCapacity = key();
    transfer(from, to, 1, overCapacity).andExpect(status().isConflict());
    assertEffects(overCapacity, 0);
    String missingSource = key();
    transfer(UUID.randomUUID(), to, 1, missingSource).andExpect(status().isNotFound());
    assertEffects(missingSource, 0);
    String missingDestination = key();
    transfer(from, UUID.randomUUID(), 1, missingDestination).andExpect(status().isNotFound());
    assertEffects(missingDestination, 0);
    assertThat(capacity(from)).isEqualTo(fromBefore);
    assertThat(capacity(to)).isEqualTo(toBefore);
  }

  @Test
  void sameAccountIsRejectedBeforeWaitingForCapacity() throws Exception {
    UUID account = account(100);
    String key = key();
    Map<String, Object> before = capacity(account);
    try (Connection blocker = appConnection();
        var pool = Executors.newSingleThreadExecutor()) {
      lock(blocker, account, false);
      try {
        var result = pool.submit(() -> transfer(account, account, 1, key).andReturn());
        assertThat(result.get(3, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(400);
      } finally {
        blocker.rollback();
      }
    }
    assertThat(capacity(account)).isEqualTo(before);
    assertEffects(key, 0);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "0",
        "-1",
        "2147483648",
        "-2147483649",
        "1.0",
        "1.5",
        "1e0",
        "\"1\"",
        "true",
        "null"
      })
  void amountMustBeAnIntegerWithinPositiveIntBounds(String amount) throws Exception {
    UUID from = account(10);
    UUID to = account(0);
    String key = key();
    request(key, body(from.toString(), to.toString(), amount, key))
        .andExpect(status().isBadRequest());
    assertEffects(key, 0);
    assertCapacity(from, 10, 0, 0);
    assertCapacity(to, 0, 0, 0);
  }

  @Test
  void invalidIdentityAndMissingFieldsLeaveNoOperation() throws Exception {
    UUID from = account(10);
    UUID to = account(0);
    String key = key();
    for (String invalid :
        new String[] {
          "{}",
          "null",
          "{",
          body("not-a-uuid", to.toString(), "1", key),
          body(from.toString(), "11111111-1111-1111-8111-111111111111", "1", key),
          body("", to.toString(), "1", key),
          body(from.toString(), to.toString(), "1", ""),
          body(from.toString(), to.toString(), "1", "x".repeat(65)),
          body(from.toString(), to.toString(), "1", "different-key"),
          "{\"fromAccountId\":\""
              + from
              + "\",\"toAccountId\":\""
              + to
              + "\",\"idempotencyKey\":\""
              + key
              + "\"}"
        }) {
      request(key, invalid).andExpect(status().isBadRequest());
    }
    mockMvc
        .perform(
            post("/v1/transfer")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(from.toString(), to.toString(), "1", key)))
        .andExpect(status().isBadRequest());
    request(" ", body(from.toString(), to.toString(), "1", " ")).andExpect(status().isBadRequest());
    assertEffects(key, 0);
    assertCapacity(from, 10, 0, 0);
    assertCapacity(to, 0, 0, 0);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void concurrentSameKeyResolvesBeforeExhaustedSourceValidation(boolean differentBody)
      throws Exception {
    UUID from = account(1);
    UUID to = account(0);
    String key = key();
    List<MvcResult> results =
        race(
            lower(from, to),
            () -> transfer(from, to, 1, key).andReturn(),
            () -> transfer(from, to, differentBody ? 2 : 1, key).andReturn());

    assertThat(results.get(0).getResponse().getStatus()).isEqualTo(200);
    assertThat(results.get(1).getResponse().getStatus()).isEqualTo(differentBody ? 422 : 200);
    if (!differentBody) {
      assertThat(results.get(1).getResponse().getContentAsByteArray())
          .isEqualTo(results.get(0).getResponse().getContentAsByteArray());
    }
    assertCapacity(from, 0, 0, 0);
    assertCapacity(to, 1, 0, 0);
    assertEffects(key, 1);
  }

  @Test
  void waitingRequestCanTakeOverAClaimAfterAnOversizedOwnerRollsBack() throws Exception {
    UUID from = account(1);
    UUID to = account(0);
    String key = key();
    List<MvcResult> results =
        race(
            lower(from, to),
            () -> transfer(from, to, 2, key).andReturn(),
            () -> transfer(from, to, 1, key).andReturn());
    assertThat(results.get(0).getResponse().getStatus()).isEqualTo(409);
    assertThat(results.get(1).getResponse().getStatus()).isEqualTo(200);
    assertCapacity(from, 0, 0, 0);
    assertCapacity(to, 1, 0, 0);
    assertEffects(key, 1);
  }

  @Test
  void oppositeDirectionsLockInLexicalOrderAndConserveCapacityThroughoutAStorm() throws Exception {
    // Crossing the signed-long boundary distinguishes lexical UUID ordering from UUID.compareTo.
    UUID low =
        UUID.fromString("70000000-0000-4000-8000-" + UUID.randomUUID().toString().substring(24));
    UUID high =
        UUID.fromString("80000000-0000-4000-8000-" + UUID.randomUUID().toString().substring(24));
    account(low, 1000);
    account(high, 1000);
    long beforeSum = capacitySum(low, high);
    try (Connection blocker = appConnection();
        var pool = Executors.newFixedThreadPool(2)) {
      lock(blocker, low, false);
      try {
        var reverse = pool.submit(() -> transfer(high, low, 1, key()).andReturn());
        awaitDatabaseWaiters(1);
        // A source-first or signed-UUID implementation would already hold the high row here.
        try (Connection observer = appConnection()) {
          lock(observer, high, true);
          observer.rollback();
        }
        var forward = pool.submit(() -> transfer(low, high, 1, key()).andReturn());
        awaitDatabaseWaiters(2);
        blocker.rollback();
        assertThat(reverse.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        assertThat(forward.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
      } finally {
        blocker.rollback();
      }
    }

    int workers = 8;
    int perWorker = 20;
    CountDownLatch start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(workers)) {
      List<Future<Void>> futures = new ArrayList<>();
      for (int worker = 0; worker < workers; worker++) {
        boolean forward = worker % 2 == 0;
        futures.add(
            pool.submit(
                () -> {
                  assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                  for (int i = 0; i < perWorker; i++) {
                    transfer(forward ? low : high, forward ? high : low, 1, key())
                        .andExpect(status().isOk());
                    assertSum(low, high, 2000);
                  }
                  return null;
                }));
      }
      start.countDown();
      for (Future<Void> future : futures) {
        future.get(30, TimeUnit.SECONDS);
      }
    }
    assertCapacity(low, 1000, 0, 0);
    assertCapacity(high, 1000, 0, 0);
    assertSum(low, high, 2000);
    int transfers = workers * perWorker + 2;
    System.out.printf(
        "Transfer SUM(total) WHERE account_id IN (%s, %s): before=%d, after=%d, transfers=%d%n",
        low, high, beforeSum, capacitySum(low, high), transfers);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_entry WHERE kind = 'TRANSFER' AND account_id IN (?, ?)",
                Integer.class,
                low,
                high))
        .isEqualTo(transfers);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE aggregate = 'transfer'"
                    + " AND payload ->> 'fromAccountId' IN (?, ?)",
                Integer.class,
                low.toString(),
                high.toString()))
        .isEqualTo(transfers);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM operation WHERE type = 'TRANSFER' AND status = 'COMPLETED'"
                    + " AND response_body ->> 'fromAccountId' IN (?, ?)",
                Integer.class,
                low.toString(),
                high.toString()))
        .isEqualTo(transfers);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void killedConnectionRollsBackAndUnobservedRetryResolvesThroughLookup(boolean afterOutbox)
      throws Exception {
    UUID from = account(100);
    UUID to = account(20);
    Map<String, Object> fromBefore = capacity(from);
    Map<String, Object> toBefore = capacity(to);
    String key = key();
    if (afterOutbox) {
      doAnswer(
              invocation -> {
                invocation.callRealMethod();
                assertCapacity(from, 0, 0, 0);
                assertCapacity(to, 120, 0, 0);
                assertEffects(key, 1);
                killTransaction();
                return null;
              })
          .when(repository)
          .insertOutbox(any(UUID.class), eq("transfer"), any(String.class));
    } else {
      doAnswer(
              invocation -> {
                invocation.callRealMethod();
                assertCapacity(from, 0, 0, 0);
                assertCapacity(to, 20, 0, 0);
                killTransaction();
                return null;
              })
          .when(repository)
          .addTotal(from, -100);
    }
    try {
      assertThatThrownBy(
              () -> transferService.transfer(from.toString(), to.toString(), 100, key, key))
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
    assertThat(capacity(from)).isEqualTo(fromBefore);
    assertThat(capacity(to)).isEqualTo(toBefore);
    assertSum(from, to, 120);
    assertEffects(key, 0);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_entry WHERE account_id IN (?, ?)",
                Integer.class,
                from,
                to))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE payload ->> 'fromAccountId' = ?",
                Integer.class,
                from.toString()))
        .isZero();
    mockMvc.perform(get("/v1/operations/{key}", key)).andExpect(status().isNotFound());

    // Commit the retry but discard its response, as a disconnected caller would.
    transferService.transfer(from.toString(), to.toString(), 100, key, key);
    byte[] lookedUp = lookup(key);
    assertThat(
            transfer(from, to, 100, key)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray())
        .isEqualTo(lookedUp);
    assertCapacity(from, 0, 0, 0);
    assertCapacity(to, 120, 0, 0);
    assertSum(from, to, 120);
    assertEffects(key, 1);
  }

  private List<MvcResult> race(UUID account, Callable<MvcResult> first, Callable<MvcResult> second)
      throws Exception {
    try (Connection blocker = appConnection();
        var pool = Executors.newFixedThreadPool(2)) {
      lock(blocker, account, false);
      try {
        var one = pool.submit(first);
        awaitDatabaseWaiters(1);
        var two = pool.submit(second);
        awaitDatabaseWaiters(2);
        blocker.rollback();
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

  private static void lock(Connection connection, UUID account, boolean nowait) throws Exception {
    connection.setAutoCommit(false);
    try (var statement =
        connection.prepareStatement(
            "SELECT 1 FROM capacity WHERE account_id = ? FOR UPDATE" + (nowait ? " NOWAIT" : ""))) {
      statement.setObject(1, account);
      try (var rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
      }
    }
  }

  private void killTransaction() throws Exception {
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

  private UUID account(int total) {
    UUID id = UUID.randomUUID();
    account(id, total);
    return id;
  }

  private void account(UUID id, int total) {
    jdbc.update("INSERT INTO account (id, display_name) VALUES (?, 'transfer-test')", id);
    jdbc.update("INSERT INTO capacity (account_id, total) VALUES (?, ?)", id, total);
  }

  private void reserve(UUID account, int amount, boolean commit) throws Exception {
    String key = key();
    mockMvc
        .perform(
            post("/v1/reserve")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"accountId\":\""
                        + account
                        + "\",\"amount\":"
                        + amount
                        + ",\"idempotencyKey\":\""
                        + key
                        + "\"}"))
        .andExpect(status().isCreated());
    if (commit) {
      UUID reservation =
          jdbc.queryForObject(
              "SELECT r.id FROM reservation r JOIN operation o ON o.id = r.operation_id"
                  + " WHERE o.idempotency_key = ?",
              UUID.class,
              key);
      String commitKey = key();
      mockMvc
          .perform(
              post("/v1/commit")
                  .header("Idempotency-Key", commitKey)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"reservationId\":\""
                          + reservation
                          + "\",\"idempotencyKey\":\""
                          + commitKey
                          + "\"}"))
          .andExpect(status().isOk());
    }
  }

  private ResultActions transfer(UUID from, UUID to, int amount, String key) throws Exception {
    return request(key, body(from.toString(), to.toString(), Integer.toString(amount), key));
  }

  private ResultActions request(String key, String body) throws Exception {
    return mockMvc.perform(
        post("/v1/transfer")
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private byte[] lookup(String key) throws Exception {
    return mockMvc
        .perform(get("/v1/operations/{key}", key))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsByteArray();
  }

  private static String body(String from, String to, String amount, String key) {
    return "{\"fromAccountId\":\""
        + from
        + "\",\"toAccountId\":\""
        + to
        + "\",\"amount\":"
        + amount
        + ",\"idempotencyKey\":\""
        + RequestHash.escape(key)
        + "\"}";
  }

  private static String key() {
    return "transfer-it-" + UUID.randomUUID();
  }

  private static UUID lower(UUID first, UUID second) {
    return first.toString().compareTo(second.toString()) < 0 ? first : second;
  }

  private Map<String, Object> capacity(UUID account) {
    return jdbc.queryForMap(
        "SELECT total, reserved, committed, available, version FROM capacity WHERE account_id = ?",
        account);
  }

  private List<Map<String, Object>> reservations(UUID from, UUID to) {
    return jdbc.queryForList(
        "SELECT * FROM reservation WHERE account_id IN (?, ?) ORDER BY id", from, to);
  }

  private void assertCapacity(UUID account, int total, int reserved, int committed) {
    assertThat(capacity(account))
        .containsEntry("total", total)
        .containsEntry("reserved", reserved)
        .containsEntry("committed", committed)
        .containsEntry("available", total - reserved - committed);
  }

  private void assertSum(UUID first, UUID second, long total) {
    assertThat(capacitySum(first, second)).isEqualTo(total);
  }

  private long capacitySum(UUID first, UUID second) {
    return jdbc.queryForObject(
        "SELECT SUM(total) FROM capacity WHERE account_id IN (?, ?)", Long.class, first, second);
  }

  private void assertEffects(String key, int expected) {
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM operation WHERE idempotency_key = ?", Integer.class, key))
        .isEqualTo(expected);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_entry a JOIN operation o ON o.id = a.operation_id"
                    + " WHERE o.idempotency_key = ?",
                Integer.class,
                key))
        .isEqualTo(expected);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox b JOIN operation o"
                    + " ON b.payload ->> 'operationId' = o.id::text WHERE o.idempotency_key = ?",
                Integer.class,
                key))
        .isEqualTo(expected);
  }

  private static String snapshots(
      UUID from,
      int fromTotal,
      int fromReserved,
      int fromCommitted,
      UUID to,
      int toTotal,
      int toReserved,
      int toCommitted) {
    return "{\"from\":"
        + snapshot(from, fromTotal, fromReserved, fromCommitted)
        + ",\"to\":"
        + snapshot(to, toTotal, toReserved, toCommitted)
        + "}";
  }

  private static String snapshot(UUID account, int total, int reserved, int committed) {
    return "{\"accountId\":\""
        + account
        + "\",\"total\":"
        + total
        + ",\"reserved\":"
        + reserved
        + ",\"committed\":"
        + committed
        + ",\"available\":"
        + (total - reserved - committed)
        + "}";
  }
}
