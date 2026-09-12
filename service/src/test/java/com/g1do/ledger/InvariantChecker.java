package com.g1do.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** O1 accounting checks at quiescent boundaries; this is not a tenant or durability proof. */
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
