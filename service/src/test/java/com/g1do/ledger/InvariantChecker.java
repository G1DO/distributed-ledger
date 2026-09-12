package com.g1do.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** SQL-backed assertions for the O1 subset of the ledger invariants (I1 through I6). */
final class InvariantChecker {

  private final JdbcTemplate jdbc;

  InvariantChecker(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  void assertO1Invariants() {
    assertCapacityIsNonnegativeAndConserved(); // I1, I2
    assertOneAuditPerCompletedOperation(); // I3
    assertOperationKeysAreUnique(); // I4
    assertNoOrphanRows(); // I5
  }

  private void assertCapacityIsNonnegativeAndConserved() {
    List<Map<String, Object>> capacities =
        jdbc.queryForList("SELECT account_id, total, reserved, committed, available FROM capacity");
    for (Map<String, Object> capacity : capacities) {
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
    }
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
