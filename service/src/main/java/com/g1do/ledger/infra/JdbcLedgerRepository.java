package com.g1do.ledger.infra;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access for the O1 slice. All statements use bound parameters. Callers own transaction
 * boundaries; every write method is intended to run inside a single {@code @Transactional} slice
 * (business row + outbox + audit same local tx).
 */
@Repository
public class JdbcLedgerRepository {

  private final JdbcTemplate jdbc;

  public JdbcLedgerRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Map<String, Object>> findOperationByKey(String key) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT id, idempotency_key, type, status, request_hash,"
                + " response_body::text AS response_body FROM operation WHERE idempotency_key = ?",
            key);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(rows.get(0));
  }

  public Optional<Map<String, Object>> lockCapacity(UUID accountId) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT account_id, total, reserved, committed, available, version"
                + " FROM capacity WHERE account_id = ? FOR UPDATE",
            accountId);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(rows.get(0));
  }

  public boolean claimOperation(UUID id, String key, String type, String requestHash) {
    return jdbc.update(
            "INSERT INTO operation (id, idempotency_key, type, status, request_hash)"
                + " VALUES (?, ?, ?, 'PENDING', ?) ON CONFLICT (idempotency_key) DO NOTHING",
            id,
            key,
            type,
            requestHash)
        == 1;
  }

  public void completeOperation(UUID id, String responseBody) {
    int updated =
        jdbc.update(
            "UPDATE operation SET status = 'COMPLETED', response_body = ?::jsonb"
                + " WHERE id = ? AND status = 'PENDING'",
            responseBody,
            id);
    if (updated != 1) {
      throw new IllegalStateException("Expected one pending operation to complete");
    }
  }

  public Optional<Map<String, Object>> lockReservation(UUID reservationId) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT id, account_id, operation_id, amount, status"
                + " FROM reservation WHERE id = ? FOR UPDATE",
            reservationId);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(rows.get(0));
  }

  public String getOperationResponseBody(UUID id) {
    return jdbc.queryForObject(
        "SELECT response_body::text FROM operation WHERE id = ?", String.class, id);
  }

  public void insertReservation(
      UUID id, UUID accountId, UUID operationId, int amount, String status) {
    jdbc.update(
        "INSERT INTO reservation (id, account_id, operation_id, amount, status)"
            + " VALUES (?, ?, ?, ?, ?)",
        id,
        accountId,
        operationId,
        amount,
        status);
  }

  public void updateReservationStatus(UUID reservationId, String status) {
    jdbc.update("UPDATE reservation SET status = ? WHERE id = ?", status, reservationId);
  }

  public void addReserved(UUID accountId, int amount) {
    jdbc.update(
        "UPDATE capacity SET reserved = reserved + ?, version = version + 1 WHERE account_id = ?",
        amount,
        accountId);
  }

  public void moveReservedToCommitted(UUID accountId, int amount) {
    jdbc.update(
        "UPDATE capacity SET reserved = reserved - ?, committed = committed + ?,"
            + " version = version + 1 WHERE account_id = ?",
        amount,
        amount,
        accountId);
  }

  public void releaseReserved(UUID accountId, int amount) {
    jdbc.update(
        "UPDATE capacity SET reserved = reserved - ?, version = version + 1 WHERE account_id = ?",
        amount,
        accountId);
  }

  public void insertOutbox(UUID id, String aggregate, String payloadJson) {
    jdbc.update(
        "INSERT INTO outbox (id, aggregate, payload) VALUES (?, ?, ?::jsonb)",
        id,
        aggregate,
        payloadJson);
  }

  public void insertAudit(
      UUID operationId,
      UUID accountId,
      String kind,
      int amount,
      String beforeJson,
      String afterJson) {
    jdbc.update(
        "INSERT INTO audit_entry (operation_id, account_id, kind, amount,"
            + " before_snapshot, after_snapshot)"
            + " VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb)",
        operationId,
        accountId,
        kind,
        amount,
        beforeJson,
        afterJson);
  }

  public Map<String, Object> getCapacity(UUID accountId) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT total, reserved, committed, available FROM capacity WHERE account_id = ?",
            accountId);
    if (rows.isEmpty()) {
      throw new com.g1do.ledger.service.LedgerNotFoundException("account not found: " + accountId);
    }
    return rows.get(0);
  }
}
