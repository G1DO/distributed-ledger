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
import com.g1do.ledger.service.ReleaseService;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.TransactionSystemException;

/** Release API, accounting, idempotency, and rollback against the real PostgreSQL transaction. */
class ReleaseIT extends PostgresITBase {

  @Autowired private ReleaseService releaseService;

  @MockitoSpyBean private JdbcLedgerRepository repository;

  @Test
  void releasePreservesCommittedCapacityAndReplaysStoredBytes() throws Exception {
    UUID account = account(1000);
    UUID untouched = account(500);
    UUID committedReservation = reserve(account, 40);
    command("commit", committedReservation, key()).andExpect(status().isOk());
    UUID reservation = reserve(account, 100);
    UUID remainingReservation = reserve(account, 30);
    assertThat(jdbc.queryForMap("SELECT expires_at FROM reservation WHERE id = ?", reservation))
        .containsEntry("expires_at", null);
    String key = "release-\"\\-" + UUID.randomUUID();
    String response =
        command("release", reservation, key)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("RELEASED"))
            .andExpect(jsonPath("$.accountId").value(account.toString()))
            .andExpect(jsonPath("$.reservationId").value(reservation.toString()))
            .andExpect(jsonPath("$.amount").value(100))
            .andExpect(jsonPath("$.idempotencyKey").value(key))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String replay =
        request(
                "release",
                key,
                "{ \"idempotencyKey\":\""
                    + RequestHash.escape(key)
                    + "\", \"reservationId\":\""
                    + reservation
                    + "\" }")
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(replay).isEqualTo(response);
    assertThat(
            mockMvc
                .perform(get("/v1/operations/{key}", key))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .isEqualTo(response);
    command("release", UUID.randomUUID(), key).andExpect(status().isUnprocessableEntity());
    new InvariantChecker(jdbc, account).assertExpectedCapacity(account, 1000, 30, 40);
    new InvariantChecker(jdbc, untouched).assertExpectedCapacity(untouched, 500, 0, 0);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM reservation WHERE id = ?", String.class, remainingReservation))
        .isEqualTo("RESERVED");
    mockMvc
        .perform(get("/v1/query").param("accountId", account.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(930));
    assertThat(
            jdbc.queryForMap(
                "SELECT kind, amount, before_snapshot ->> 'reserved' AS before_reserved,"
                    + " after_snapshot ->> 'reserved' AS after_reserved,"
                    + " before_snapshot ->> 'available' AS before_available,"
                    + " after_snapshot ->> 'available' AS after_available,"
                    + " after_snapshot ->> 'committed' AS committed,"
                    + " after_snapshot ->> 'total' AS total"
                    + " FROM audit_entry a JOIN operation o ON o.id = a.operation_id"
                    + " WHERE o.idempotency_key = ?",
                key))
        .containsEntry("kind", "RELEASE")
        .containsEntry("amount", 100)
        .containsEntry("before_reserved", "130")
        .containsEntry("after_reserved", "30")
        .containsEntry("before_available", "830")
        .containsEntry("after_available", "930")
        .containsEntry("committed", "40")
        .containsEntry("total", "1000");
    assertThat(
            jdbc.queryForMap(
                "SELECT aggregate, dispatched, payload ->> 'reservationId' AS reservation_id,"
                    + " payload ->> 'amount' AS amount FROM outbox b JOIN operation o"
                    + " ON b.payload ->> 'operationId' = o.id::text WHERE o.idempotency_key = ?",
                key))
        .containsEntry("aggregate", "release")
        .containsEntry("dispatched", false)
        .containsEntry("reservation_id", reservation.toString())
        .containsEntry("amount", "100");
    assertEffects(account, 5);
  }

  @Test
  void fullIntegerCapacityCanBeReleasedAndReservedAgain() throws Exception {
    UUID account = account(Integer.MAX_VALUE);
    UUID reservation = reserve(account, Integer.MAX_VALUE);
    command("release", reservation, key()).andExpect(status().isOk());
    new InvariantChecker(jdbc, account).assertExpectedCapacity(account, Integer.MAX_VALUE, 0, 0);
    reserve(account, Integer.MAX_VALUE);
    new InvariantChecker(jdbc, account)
        .assertExpectedCapacity(account, Integer.MAX_VALUE, Integer.MAX_VALUE, 0);
  }

  @ParameterizedTest
  @ValueSource(strings = {"COMMITTED", "RELEASED", "EXPIRED"})
  void terminalReservationsRejectNewCommitAndReleaseWithoutEffects(String terminal)
      throws Exception {
    UUID account = account(100);
    UUID reservation = reserve(account, 100);
    if (terminal.equals("EXPIRED")) {
      // O2-3 will produce this state. Here only recognition and immutability are under test.
      jdbc.update("UPDATE reservation SET status = 'EXPIRED' WHERE id = ?", reservation);
      jdbc.update("UPDATE capacity SET reserved = 0 WHERE account_id = ?", account);
    } else {
      command(terminal.equals("COMMITTED") ? "commit" : "release", reservation, key())
          .andExpect(status().isOk());
    }
    Map<String, Object> before = capacity(account);
    for (String operation : new String[] {"commit", "release"}) {
      String key = key();
      command(operation, reservation, key).andExpect(status().isConflict());
      assertNoOperation(key);
    }
    assertThat(capacity(account)).isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM reservation WHERE id = ?", String.class, reservation))
        .isEqualTo(terminal);
    assertEffects(account, terminal.equals("EXPIRED") ? 1 : 2);
  }

  @Test
  void unknownReservationRollsBackClaimAndAllowsSameKeyRetry() throws Exception {
    String key = key();
    command("release", UUID.randomUUID(), key).andExpect(status().isNotFound());
    assertNoOperation(key);
    UUID account = account(100);
    UUID reservation = reserve(account, 100);
    command("release", reservation, key).andExpect(status().isOk());
    assertEffects(account, 2);
  }

  @Test
  void invalidRequestsLeaveNoOperation() throws Exception {
    String key = key();
    String reservation = UUID.randomUUID().toString();
    for (String invalid :
        new String[] {
          "{}",
          "null",
          "{",
          body("not-a-uuid", key),
          body("11111111-1111-1111-8111-111111111111", key),
          body("", key),
          body(reservation, ""),
          body(reservation, "x".repeat(65)),
          body(reservation, "different-key")
        }) {
      request("release", key, invalid).andExpect(status().isBadRequest());
    }
    mockMvc
        .perform(
            post("/v1/release")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(reservation, key)))
        .andExpect(status().isBadRequest());
    request("release", " ", body(reservation, " ")).andExpect(status().isBadRequest());
    request("release", "x".repeat(65), body(reservation, "x".repeat(65)))
        .andExpect(status().isBadRequest());
    assertNoOperation(key);
  }

  @Test
  void capacityConstraintFailureRollsBackStatusAndAllNewRows() throws Exception {
    UUID account = account(100);
    UUID reservation = reserve(account, 100);
    // Deliberate pre-existing counter drift: the DB backstop must reject a negative reserved.
    jdbc.update("UPDATE capacity SET reserved = 99 WHERE account_id = ?", account);
    String key = key();
    command("release", reservation, key).andExpect(status().isConflict());
    assertReserved(account, reservation, key, 99);
    jdbc.update("UPDATE capacity SET reserved = 100 WHERE account_id = ?", account);
    command("release", reservation, key).andExpect(status().isOk());
    assertEffects(account, 2);
  }

  @ParameterizedTest
  @ValueSource(strings = {"status", "outbox"})
  void killedConnectionRollsBackRealReleaseAndRetryCreatesOneEffect(String checkpoint)
      throws Exception {
    UUID account = account(100);
    UUID reservation = reserve(account, 100);
    String key = key();
    if (checkpoint.equals("status")) {
      doAnswer(
              invocation -> {
                invocation.callRealMethod();
                killTransaction(reservation);
                return null;
              })
          .when(repository)
          .updateReservationStatus(reservation, "RELEASED");
    } else {
      doAnswer(
              invocation -> {
                invocation.callRealMethod();
                killTransaction(reservation);
                return null;
              })
          .when(repository)
          .insertOutbox(any(UUID.class), eq("release"), any(String.class));
    }
    try {
      assertThatThrownBy(() -> releaseService.release(reservation.toString(), key, key))
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
    assertReserved(account, reservation, key, 100);
    String response =
        command("release", reservation, key)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(
            command("release", reservation, key)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .isEqualTo(response);
    new InvariantChecker(jdbc, account).assertExpectedCapacity(account, 100, 0, 0);
    assertEffects(account, 2);
  }

  private void killTransaction(UUID reservation) throws Exception {
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM reservation WHERE id = ?", String.class, reservation))
        .isEqualTo("RELEASED");
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
    UUID account = UUID.randomUUID();
    jdbc.update("INSERT INTO account (id, display_name) VALUES (?, 'release-test')", account);
    jdbc.update("INSERT INTO capacity (account_id, total) VALUES (?, ?)", account, total);
    return account;
  }

  private UUID reserve(UUID account, int amount) throws Exception {
    String key = key();
    request(
            "reserve",
            key,
            "{\"accountId\":\""
                + account
                + "\",\"amount\":"
                + amount
                + ",\"idempotencyKey\":\""
                + key
                + "\"}")
        .andExpect(status().isCreated());
    return jdbc.queryForObject(
        "SELECT r.id FROM reservation r JOIN operation o"
            + " ON o.id = r.operation_id WHERE o.idempotency_key = ?",
        UUID.class,
        key);
  }

  private ResultActions command(String operation, UUID reservation, String key) throws Exception {
    return request(operation, key, body(reservation.toString(), key));
  }

  private ResultActions request(String operation, String key, String body) throws Exception {
    return mockMvc.perform(
        post("/v1/" + operation)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private static String body(String reservation, String key) {
    return "{\"reservationId\":\""
        + reservation
        + "\",\"idempotencyKey\":\""
        + RequestHash.escape(key)
        + "\"}";
  }

  private static String key() {
    return "release-it-" + UUID.randomUUID();
  }

  private Map<String, Object> capacity(UUID account) {
    return jdbc.queryForMap(
        "SELECT total, reserved, committed, available, version"
            + " FROM capacity WHERE account_id = ?",
        account);
  }

  private void assertNoOperation(String key) {
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM operation WHERE idempotency_key = ?", Integer.class, key))
        .isZero();
  }

  private void assertReserved(UUID account, UUID reservation, String key, int reserved) {
    assertNoOperation(key);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM reservation WHERE id = ?", String.class, reservation))
        .isEqualTo("RESERVED");
    new InvariantChecker(jdbc, account).assertExpectedCapacity(account, 100, reserved, 0);
    assertThat(capacity(account)).containsEntry("version", 1L);
    assertEffects(account, 1);
  }

  private void assertEffects(UUID account, int expected) {
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_entry WHERE account_id = ?", Integer.class, account))
        .isEqualTo(expected);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE payload ->> 'accountId' = ?",
                Integer.class,
                account.toString()))
        .isEqualTo(expected);
  }
}
