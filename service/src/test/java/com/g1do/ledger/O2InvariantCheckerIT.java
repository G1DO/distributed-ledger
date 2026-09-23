package com.g1do.ledger;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.g1do.ledger.service.ReleaseService;
import com.g1do.ledger.service.ReserveService;
import com.g1do.ledger.service.TransferService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Negative controls ensure the O2 checker detects effects beyond the generated capacity equation.
 */
@Transactional
class O2InvariantCheckerIT extends PostgresITBase {
  @Autowired ReserveService reserve;
  @Autowired ReleaseService release;
  @Autowired TransferService transfer;

  @Test
  void rejectsReleaseThatDidNotReturnItsReservedUnits() {
    UUID account = account();
    String key = "control-" + UUID.randomUUID();
    reserve.reserve(account.toString(), 10, key, null, key);
    UUID reservation =
        jdbc.queryForObject("SELECT id FROM reservation WHERE account_id = ?", UUID.class, account);
    String releaseKey = "control-" + UUID.randomUUID();
    release.release(reservation.toString(), releaseKey, releaseKey);
    // Make records and counters agree with each other, while contradicting the release audit.
    jdbc.update("UPDATE reservation SET status = 'RESERVED' WHERE id = ?", reservation);
    jdbc.update("UPDATE capacity SET reserved = 10 WHERE account_id = ?", account);
    assertThatThrownBy(
            () -> new InvariantChecker(jdbc, account).assertO2Invariants(Map.of(account, 100)))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("reserved matches O2 audit effects");
  }

  @Test
  void rejectsTransferWithMissingDestinationCredit() {
    UUID source = account();
    UUID destination = account();
    String key = "control-" + UUID.randomUUID();
    transfer.transfer(source.toString(), destination.toString(), 10, key, key);
    jdbc.update("UPDATE capacity SET total = total - 10 WHERE account_id = ?", destination);
    assertThatThrownBy(
            () ->
                new InvariantChecker(jdbc, source, destination)
                    .assertO2Invariants(Map.of(source, 100, destination, 100)))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("total matches initial total and transfer audit effects");
  }

  @Test
  void rejectsDuplicateOutboxForTransfer() {
    UUID source = account();
    UUID destination = account();
    String key = "control-" + UUID.randomUUID();
    transfer.transfer(source.toString(), destination.toString(), 10, key, key);
    jdbc.update(
        "INSERT INTO outbox (id, aggregate, payload) SELECT ?, b.aggregate, b.payload"
            + " FROM outbox b JOIN operation o ON b.payload ->> 'operationId' = o.id::text"
            + " WHERE o.idempotency_key = ?",
        UUID.randomUUID(),
        key);
    assertThatThrownBy(
            () ->
                new InvariantChecker(jdbc, source, destination)
                    .assertO2Invariants(Map.of(source, 100, destination, 100)))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("one matching audit and outbox");
  }

  @Test
  void rejectsCompletedExpireWithoutAtomicEffects() {
    UUID account = account();
    jdbc.update(
        "INSERT INTO operation (id, idempotency_key, type, status, request_hash, response_body)"
            + " VALUES (?, ?, 'EXPIRE', 'COMPLETED', 'fixture', ?::jsonb)",
        UUID.randomUUID(),
        "control-" + UUID.randomUUID(),
        "{\"accountId\":\"" + account + "\"}");
    assertThatThrownBy(
            () -> new InvariantChecker(jdbc, account).assertO2Invariants(Map.of(account, 100)))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("one matching audit and outbox");
  }

  private UUID account() {
    UUID account = UUID.randomUUID();
    jdbc.update("INSERT INTO account (id, display_name) VALUES (?, 'o2-checker-control')", account);
    jdbc.update("INSERT INTO capacity (account_id, total) VALUES (?, 100)", account);
    return account;
  }

  @Test
  void rejectsLeakedPendingClaimWithoutAnyBusinessRows() {
    UUID account = account();
    String prefix = "control-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO operation (id, idempotency_key, type, status, request_hash)"
            + " VALUES (?, ?, 'RESERVE', 'PENDING', 'fixture')",
        UUID.randomUUID(),
        prefix + "0");
    assertThatThrownBy(
            () ->
                new InvariantChecker(jdbc, account).assertCompletedOperationKeys(prefix, List.of()))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("durable operation keys equal accepted model commands");
  }

  @Test
  void rejectsDisconnectedOutboxEffect() {
    UUID account = account();
    jdbc.update(
        "INSERT INTO outbox (id, aggregate, payload) VALUES (?, 'reservation', ?::jsonb)",
        UUID.randomUUID(),
        "{\"accountId\":\"" + account + "\",\"operationId\":\"" + UUID.randomUUID() + "\"}");
    assertThatThrownBy(
            () -> new InvariantChecker(jdbc, account).assertO2Invariants(Map.of(account, 100)))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("outbox has an operation");
  }

  @Test
  void rejectsNoOpExpiryThatRetainedItsInternalClaim() {
    UUID account = account();
    UUID reservation = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO operation (id, idempotency_key, type, status, request_hash)"
            + " VALUES (?, ?, 'EXPIRE', 'PENDING', 'fixture')",
        UUID.randomUUID(),
        "internal-expire:" + reservation + ":" + UUID.randomUUID());
    assertThatThrownBy(
            () ->
                new InvariantChecker(jdbc, account)
                    .assertCompletedExpiryOperations(List.of(reservation), 0))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("internal expiry keys equal accepted model expirations");
  }
}
