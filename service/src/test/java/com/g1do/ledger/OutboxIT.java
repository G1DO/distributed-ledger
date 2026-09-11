package com.g1do.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * outbox is table-only in O1 (no relay worker): rows default to undispatched, payload is mandatory,
 * and app_role can stage plus mark rows dispatched for the O1-3 same-tx pattern.
 */
class OutboxIT extends PostgresITBase {

  @Test
  void insertedRowDefaultsToUndispatched() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO outbox (id, aggregate, payload) VALUES (?, ?, ?::jsonb)",
        id,
        "outbox-default-" + UUID.randomUUID(),
        "{\"reservationId\":\"" + UUID.randomUUID() + "\"}");

    Boolean dispatched =
        jdbc.queryForObject("SELECT dispatched FROM outbox WHERE id = ?", Boolean.class, id);
    assertThat(dispatched).isFalse();
  }

  @Test
  void nullPayloadRejectedWithNoRow() {
    String aggregate = "outbox-notnull-" + UUID.randomUUID();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO outbox (id, aggregate, payload) VALUES (?, ?, ?::jsonb)",
                    UUID.randomUUID(),
                    aggregate,
                    null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .satisfies(
            failure -> {
              SQLException root = rootSQLException(failure);
              assertThat((Object) root).isNotNull();
              assertThat(root.getSQLState()).isEqualTo("23502");
            });

    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox WHERE aggregate = ?", Integer.class, aggregate);
    assertThat(count).isEqualTo(0);
  }

  @Test
  void appRoleCanStageAndMarkDispatched() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO outbox (id, aggregate, payload) VALUES (?, ?, ?::jsonb)",
        id,
        "outbox-dispatch-" + UUID.randomUUID(),
        "{\"operationId\":\"" + UUID.randomUUID() + "\"}");

    int updated = jdbc.update("UPDATE outbox SET dispatched = TRUE WHERE id = ?", id);
    assertThat(updated).isEqualTo(1);

    Boolean dispatched =
        jdbc.queryForObject("SELECT dispatched FROM outbox WHERE id = ?", Boolean.class, id);
    assertThat(dispatched).isTrue();
  }
}
