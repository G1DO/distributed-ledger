package com.g1do.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.g1do.ledger.service.OperationResult;
import com.g1do.ledger.service.ReserveService;
import com.g1do.ledger.service.TransactionCheckpoint;
import com.g1do.ledger.service.TransactionProbe;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Seven-point crash matrix for a reserve. Pre-commit failures are injected into the real service
 * transaction; the connection-loss case closes a JDBC transaction without committing. A caller that
 * loses its response after commit proves durability by replaying the same key.
 */
@Import(KillMidTxIT.FailureInjectionConfiguration.class)
class KillMidTxIT extends PostgresITBase {

  @Autowired private ReserveService reserveService;

  @Autowired private ControlledTransactionProbe transactionProbe;

  @AfterEach
  void clearFailure() {
    transactionProbe.clear();
  }

  @Test
  void crashMatrixLeavesNoPartialStateAndReplayIsSafe() {
    for (TransactionCheckpoint checkpoint : TransactionCheckpoint.values()) {
      UUID accountId = account("failpoint-" + checkpoint.name());
      String key = "c-" + checkpoint.ordinal() + "-" + UUID.randomUUID();

      transactionProbe.failAt(checkpoint);
      assertThatThrownBy(() -> reserveService.reserve(accountId.toString(), 100, key, null, key))
          .isInstanceOf(SimulatedRequestFailure.class);

      assertNoPartialReserve(accountId, key);
      transactionProbe.clear();
      assertReplayCreatesExactlyOneReserve(accountId, key);
    }
  }

  @Test
  void beforeTransactionHasNoStateAndAReplayCreatesOneReserve() {
    UUID accountId = account("before-transaction");
    String key = "crash-before-tx-" + UUID.randomUUID();

    assertNoPartialReserve(accountId, key);
    assertReplayCreatesExactlyOneReserve(accountId, key);
  }

  @Test
  void connectionLossBeforeCommitRollsBackAllWrites() throws Exception {
    UUID accountId = account("connection-loss");
    String key = "crash-disconnect-" + UUID.randomUUID();

    Connection connection = appConnection();
    connection.setAutoCommit(false);
    try {
      insertReserveRows(connection, accountId, key);
      // Closing an uncommitted PostgreSQL session is the database-level equivalent of a process
      // disconnect: PostgreSQL aborts the transaction before releasing its locks.
    } finally {
      connection.close();
    }

    assertNoPartialReserve(accountId, key);
    assertReplayCreatesExactlyOneReserve(accountId, key);
  }

  @Test
  void responseLostAfterCommitReplaysThePersistedResult() {
    UUID accountId = account("after-commit");
    String key = "crash-after-commit-" + UUID.randomUUID();

    OperationResult committed = reserveService.reserve(accountId.toString(), 100, key, null, key);
    OperationResult replay = reserveService.reserve(accountId.toString(), 100, key, null, key);

    assertThat(replay.replayed()).isTrue();
    assertThat(replay.responseBody()).isEqualTo(committed.responseBody());
    assertExactlyOneReserve(accountId, key);
  }

  private UUID account(String displayName) {
    UUID accountId = UUID.randomUUID();
    jdbc.update("INSERT INTO account (id, display_name) VALUES (?, ?)", accountId, displayName);
    jdbc.update(
        "INSERT INTO capacity (account_id, total, reserved, committed) VALUES (?, 500, 0, 0)",
        accountId);
    return accountId;
  }

  private void assertReplayCreatesExactlyOneReserve(UUID accountId, String key) {
    OperationResult first = reserveService.reserve(accountId.toString(), 100, key, null, key);
    OperationResult replay = reserveService.reserve(accountId.toString(), 100, key, null, key);
    assertThat(first.replayed()).isFalse();
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.responseBody()).isEqualTo(first.responseBody());
    assertExactlyOneReserve(accountId, key);
  }

  private void assertNoPartialReserve(UUID accountId, String key) {
    Integer operations = count("SELECT COUNT(*) FROM operation WHERE idempotency_key = ?", key);
    Integer reservations =
        count("SELECT COUNT(*) FROM reservation WHERE account_id = ?", accountId);
    Integer audits = count("SELECT COUNT(*) FROM audit_entry WHERE account_id = ?", accountId);
    Integer outbox =
        count(
            "SELECT COUNT(*) FROM outbox WHERE payload ->> 'accountId' = ?", accountId.toString());
    Map<String, Object> capacity = capacity(accountId);

    assertThat(operations).isZero();
    assertThat(reservations).isZero();
    assertThat(audits).isZero();
    assertThat(outbox).isZero();
    assertThat(((Number) capacity.get("total")).intValue()).isEqualTo(500);
    assertThat(((Number) capacity.get("reserved")).intValue()).isZero();
    assertThat(((Number) capacity.get("committed")).intValue()).isZero();
    assertThat(((Number) capacity.get("available")).intValue()).isEqualTo(500);
  }

  private void assertExactlyOneReserve(UUID accountId, String key) {
    assertThat(count("SELECT COUNT(*) FROM operation WHERE idempotency_key = ?", key)).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM reservation WHERE account_id = ?", accountId))
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM audit_entry WHERE account_id = ?", accountId))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT COUNT(*) FROM outbox WHERE payload ->> 'accountId' = ?",
                accountId.toString()))
        .isEqualTo(1);
    Map<String, Object> capacity = capacity(accountId);
    assertThat(((Number) capacity.get("reserved")).intValue()).isEqualTo(100);
    assertThat(((Number) capacity.get("available")).intValue()).isEqualTo(400);
  }

  private Integer count(String sql, Object parameter) {
    return jdbc.queryForObject(sql, Integer.class, parameter);
  }

  private Map<String, Object> capacity(UUID accountId) {
    return jdbc.queryForMap(
        "SELECT total, reserved, committed, available FROM capacity WHERE account_id = ?",
        accountId);
  }

  private void insertReserveRows(Connection connection, UUID accountId, String key)
      throws Exception {
    UUID operationId = UUID.randomUUID();
    UUID reservationId = UUID.randomUUID();
    try (PreparedStatement capacity =
            connection.prepareStatement(
                "UPDATE capacity SET reserved = reserved + 100 WHERE account_id = ?");
        PreparedStatement operation =
            connection.prepareStatement(
                "INSERT INTO operation (id, idempotency_key, type, status, request_hash, response_body)"
                    + " VALUES (?, ?, 'RESERVE', 'COMPLETED', 'disconnect-hash', '{}'::jsonb)");
        PreparedStatement reservation =
            connection.prepareStatement(
                "INSERT INTO reservation (id, account_id, operation_id, amount, status)"
                    + " VALUES (?, ?, ?, 100, 'RESERVED')")) {
      capacity.setObject(1, accountId);
      capacity.executeUpdate();
      operation.setObject(1, operationId);
      operation.setString(2, key);
      operation.executeUpdate();
      reservation.setObject(1, reservationId);
      reservation.setObject(2, accountId);
      reservation.setObject(3, operationId);
      reservation.executeUpdate();
    }
  }

  @TestConfiguration
  static class FailureInjectionConfiguration {
    @Bean
    @Primary
    ControlledTransactionProbe controlledTransactionProbe() {
      return new ControlledTransactionProbe();
    }
  }

  static class ControlledTransactionProbe implements TransactionProbe {
    private final ThreadLocal<EnumSet<TransactionCheckpoint>> failures =
        ThreadLocal.withInitial(() -> EnumSet.noneOf(TransactionCheckpoint.class));

    void failAt(TransactionCheckpoint checkpoint) {
      failures.get().add(checkpoint);
    }

    void clear() {
      failures.remove();
    }

    @Override
    public void reached(TransactionCheckpoint checkpoint) {
      if (failures.get().contains(checkpoint)) {
        throw new SimulatedRequestFailure(checkpoint);
      }
    }
  }

  static class SimulatedRequestFailure extends RuntimeException {
    SimulatedRequestFailure(TransactionCheckpoint checkpoint) {
      super("simulated failure at " + checkpoint);
    }
  }
}
