package com.g1do.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** UNIQUE on operation.idempotency_key is the idempotency hinge: no second row, ever. */
class IdempotencyKeyUniqueIT extends PostgresITBase {

  @Test
  void duplicateIdempotencyKeyFailsWithNoSecondRow() {
    String key = "idem-key-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO operation (id, idempotency_key, type, status, request_hash)"
            + " VALUES (?, ?, 'RESERVE', 'PENDING', 'hash-a')",
        UUID.randomUUID(),
        key);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO operation (id, idempotency_key, type, status, request_hash)"
                        + " VALUES (?, ?, 'RESERVE', 'PENDING', 'hash-a')",
                    UUID.randomUUID(),
                    key))
        .isInstanceOf(DataIntegrityViolationException.class)
        .satisfies(
            failure -> {
              SQLException root = rootSQLException(failure);
              assertThat((Object) root).isNotNull();
              assertThat(root.getSQLState()).isEqualTo("23505");
            });

    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM operation WHERE idempotency_key = ?", Integer.class, key);
    assertThat(count).isEqualTo(1);
  }
}
