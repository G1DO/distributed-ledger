package com.g1do.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Accounting checks at quiescent boundaries; this is not a tenant or durability proof. */
final class InvariantChecker {

  private final JdbcTemplate jdbc;
  private final List<UUID> accounts;

  InvariantChecker(JdbcTemplate jdbc, UUID... accounts) {
    this.jdbc = jdbc;
    this.accounts = List.of(accounts);
    if (accounts.length == 0) {
      throw new IllegalArgumentException("Explicit fixture accounts are required");
    }
  }

  void assertO1Invariants() {
    assertCapacityIsNonnegativeAndConserved(); // I1, I2
    assertOneAuditPerCompletedOperation(); // I3
    assertOperationKeysAreUnique(); // I4
    assertNoOrphanRows(); // I5
  }

  /** Initial totals are independent fixture inputs, never inferred from the persisted ledger. */
  void assertO2Invariants(Map<UUID, Integer> initialTotals) {
    assertThat(initialTotals.keySet()).containsExactlyInAnyOrderElementsOf(accounts);
    for (UUID account : accounts) {
      Map<String, Object> row =
          jdbc.queryForMap(
              "SELECT c.*,"
                  + " (SELECT COALESCE(SUM(amount), 0) FROM reservation"
                  + " WHERE account_id = c.account_id AND status = 'RESERVED') AS live_reserved,"
                  + " (SELECT COALESCE(SUM(amount), 0) FROM reservation"
                  + " WHERE account_id = c.account_id AND status = 'COMMITTED') AS live_committed,"
                  + " (SELECT COALESCE(SUM(CASE kind WHEN 'RESERVE' THEN amount"
                  + " WHEN 'COMMIT' THEN -amount WHEN 'RELEASE' THEN -amount"
                  + " WHEN 'EXPIRE' THEN -amount ELSE 0 END), 0) FROM audit_entry"
                  + " WHERE account_id = c.account_id) AS audit_reserved,"
                  + " (SELECT COALESCE(SUM(amount), 0) FROM audit_entry"
                  + " WHERE account_id = c.account_id AND kind = 'COMMIT') AS audit_committed,"
                  + " (SELECT COALESCE(SUM(CASE WHEN after_snapshot #>> '{from,accountId}'"
                  + " = c.account_id::text THEN -amount ELSE amount END), 0) FROM audit_entry"
                  + " WHERE kind = 'TRANSFER' AND (after_snapshot #>> '{from,accountId}'"
                  + " = c.account_id::text OR after_snapshot #>> '{to,accountId}'"
                  + " = c.account_id::text)) AS transferred"
                  + " FROM capacity c WHERE account_id = ?",
              account);
      long reserved = number(row, "reserved");
      long committed = number(row, "committed");
      assertThat(number(row, "available")).as("I1 available for %s", account).isNotNegative();
      assertThat(reserved).as("I1 reserved for %s", account).isNotNegative();
      assertThat(committed).as("I1 committed for %s", account).isNotNegative();
      assertThat((long) number(row, "total"))
          .as("I2 total equation for %s", account)
          .isEqualTo(number(row, "available") + reserved + committed);
      assertThat(reserved)
          .as("I2 reserved matches reservation records for %s", account)
          .isEqualTo(((Number) row.get("live_reserved")).longValue());
      assertThat(committed)
          .as("I2 committed matches reservation records for %s", account)
          .isEqualTo(((Number) row.get("live_committed")).longValue());
      assertThat(reserved)
          .as("I2 reserved matches O2 audit effects for %s", account)
          .isEqualTo(((Number) row.get("audit_reserved")).longValue());
      assertThat(committed)
          .as("I2 committed matches O2 audit effects for %s", account)
          .isEqualTo(((Number) row.get("audit_committed")).longValue());
      assertThat((long) number(row, "total"))
          .as("I2 total matches initial total and transfer audit effects for %s", account)
          .isEqualTo(initialTotals.get(account) + ((Number) row.get("transferred")).longValue());
      Integer invalid =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM operation o WHERE (o.response_body ->> 'accountId' = ?"
                  + " OR o.response_body ->> 'fromAccountId' = ?"
                  + " OR o.response_body ->> 'toAccountId' = ?"
                  + " OR EXISTS (SELECT 1 FROM reservation r WHERE r.operation_id = o.id"
                  + " AND r.account_id = ?)"
                  + " OR EXISTS (SELECT 1 FROM audit_entry a WHERE a.operation_id = o.id"
                  + " AND a.account_id = ?)) AND (o.status <> 'COMPLETED'"
                  + " OR (SELECT COUNT(*) FROM audit_entry a WHERE a.operation_id = o.id) <> 1"
                  + " OR (SELECT COUNT(*) FROM audit_entry a WHERE a.operation_id = o.id"
                  + " AND a.kind = o.type) <> 1"
                  + " OR (SELECT COUNT(*) FROM outbox b"
                  + " WHERE b.payload ->> 'operationId' = o.id::text) <> 1)",
              Integer.class,
              account.toString(),
              account.toString(),
              account.toString(),
              account,
              account);
      assertThat(invalid)
          .as("I3/I5 each O2 effect has one matching audit and outbox for %s", account)
          .isZero();
      Integer orphanOutbox =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM outbox b LEFT JOIN operation o"
                  + " ON b.payload ->> 'operationId' = o.id::text"
                  + " WHERE (b.payload ->> 'accountId' = ?"
                  + " OR b.payload ->> 'fromAccountId' = ?"
                  + " OR b.payload ->> 'toAccountId' = ?) AND o.id IS NULL",
              Integer.class,
              account.toString(),
              account.toString(),
              account.toString());
      assertThat(orphanOutbox).as("I5 outbox has an operation for %s", account).isZero();
    }
  }

  void assertCompletedOperationKeys(String fixturePrefix, List<String> expected) {
    List<Map<String, Object>> operations =
        jdbc.queryForList(
            "SELECT idempotency_key, status FROM operation WHERE idempotency_key LIKE ?",
            fixturePrefix + "%");
    assertThat(operations.stream().map(row -> (String) row.get("idempotency_key")).toList())
        .as("I4/I5 durable operation keys equal accepted model commands")
        .containsExactlyInAnyOrderElementsOf(expected);
    assertThat(operations.stream().map(row -> (String) row.get("status")).toList())
        .as("I5 no unfinished operation claims at quiescence")
        .allMatch("COMPLETED"::equals);
  }

  void assertCompletedExpiryOperations(List<UUID> reservationIds, int expected) {
    String placeholders =
        String.join(",", java.util.Collections.nCopies(reservationIds.size(), "?"));
    List<Map<String, Object>> operations =
        jdbc.queryForList(
            "SELECT idempotency_key, status FROM operation"
                + " WHERE idempotency_key LIKE 'internal-expire:%'"
                + " AND split_part(idempotency_key, ':', 2) IN ("
                + placeholders
                + ")",
            reservationIds.stream().map(UUID::toString).toArray());
    assertThat(operations)
        .as("I4/I5 internal expiry keys equal accepted model expirations")
        .hasSize(expected);
    assertThat(operations.stream().map(row -> (String) row.get("status")).toList())
        .as("I5 no unfinished internal expiry claims at quiescence")
        .allMatch("COMPLETED"::equals);
  }

  private void assertCapacityIsNonnegativeAndConserved() {
    for (UUID account : accounts) {
      Map<String, Object> capacity =
          jdbc.queryForMap(
              "SELECT account_id, total, reserved, committed, available FROM capacity WHERE account_id = ?",
              account);
      int total = number(capacity, "total");
      int reserved = number(capacity, "reserved");
      int committed = number(capacity, "committed");
      int available = number(capacity, "available");
      assertThat(available)
          .as("I1 available for %s", capacity.get("account_id"))
          .isGreaterThanOrEqualTo(0);
      assertThat(total)
          .as("I2 total for %s", capacity.get("account_id"))
          .isEqualTo(available + reserved + committed);
      Map<String, Object> reservations =
          jdbc.queryForMap(
              "SELECT COALESCE(SUM(amount) FILTER (WHERE status = 'RESERVED'), 0) AS reserved,"
                  + " COALESCE(SUM(amount) FILTER (WHERE status = 'COMMITTED'), 0) AS committed"
                  + " FROM reservation WHERE account_id = ?",
              account);
      assertThat((long) reserved)
          .as("reserved matches reservation records for %s", account)
          .isEqualTo(((Number) reservations.get("reserved")).longValue());
      assertThat((long) committed)
          .as("committed matches reservation records for %s", account)
          .isEqualTo(((Number) reservations.get("committed")).longValue());
      Map<String, Object> audit =
          jdbc.queryForMap(
              "SELECT COALESCE(SUM(amount) FILTER (WHERE kind = 'RESERVE'), 0) AS reserved_ever,"
                  + " COALESCE(SUM(amount) FILTER (WHERE kind = 'COMMIT'), 0) AS committed"
                  + " FROM audit_entry WHERE account_id = ?",
              account);
      assertThat((long) reserved + committed)
          .as("capacity matches reserve audit effects for %s", account)
          .isEqualTo(((Number) audit.get("reserved_ever")).longValue());
      assertThat((long) committed)
          .as("capacity matches commit audit effects for %s", account)
          .isEqualTo(((Number) audit.get("committed")).longValue());
    }
  }

  void assertExpectedCapacity(UUID account, int total, int reserved, int committed) {
    Map<String, Object> actual =
        jdbc.queryForMap(
            "SELECT total, reserved, committed, available FROM capacity WHERE account_id = ?",
            account);
    assertThat(number(actual, "total")).as("model total for %s", account).isEqualTo(total);
    assertThat(number(actual, "reserved")).as("model reserved for %s", account).isEqualTo(reserved);
    assertThat(number(actual, "committed"))
        .as("model committed for %s", account)
        .isEqualTo(committed);
    assertThat(number(actual, "available"))
        .as("model available for %s", account)
        .isEqualTo(total - reserved - committed);
  }

  private void assertOneAuditPerCompletedOperation() {
    Integer missingOrDuplicate =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM ("
                + " SELECT o.id FROM operation o LEFT JOIN audit_entry a ON a.operation_id = o.id"
                + " WHERE o.status = 'COMPLETED' AND o.type IN ('RESERVE', 'COMMIT')"
                + " GROUP BY o.id HAVING COUNT(a.id) <> 1"
                + ") invalid_operations",
            Integer.class);
    assertThat(missingOrDuplicate).as("I3 completed operations have one audit entry").isZero();
  }

  private void assertOperationKeysAreUnique() {
    Integer duplicates =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM (SELECT idempotency_key FROM operation"
                + " GROUP BY idempotency_key HAVING COUNT(*) > 1) duplicate_keys",
            Integer.class);
    assertThat(duplicates).as("I4 idempotency keys are unique").isZero();
  }

  private void assertNoOrphanRows() {
    Integer orphanReservations =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM reservation r LEFT JOIN operation o ON o.id = r.operation_id"
                + " WHERE o.id IS NULL",
            Integer.class);
    Integer orphanAudits =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_entry a LEFT JOIN operation o ON o.id = a.operation_id"
                + " WHERE o.id IS NULL",
            Integer.class);
    Integer completedOperationsWithoutOutbox =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM operation o WHERE o.status = 'COMPLETED'"
                + " AND o.type IN ('RESERVE', 'COMMIT')"
                + " AND NOT EXISTS (SELECT 1 FROM outbox b"
                + " WHERE b.payload ->> 'operationId' = o.id::text)",
            Integer.class);
    assertThat(orphanReservations).as("I5 reservations have an operation").isZero();
    assertThat(orphanAudits).as("I5 audit entries have an operation").isZero();
    assertThat(completedOperationsWithoutOutbox)
        .as("I5 completed operations have an outbox row")
        .isZero();
  }

  private static int number(Map<String, Object> row, String column) {
    return ((Number) row.get(column)).intValue();
  }
}
