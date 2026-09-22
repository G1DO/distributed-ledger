package com.g1do.ledger.service;

import com.g1do.ledger.domain.RequestHash;
import com.g1do.ledger.domain.ReservationStatus;
import com.g1do.ledger.infra.JdbcLedgerRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Commit slice: {@code RESERVED -> COMMITTED} atomically. Second commit under the same key returns
 * the original body with no second audit row and no double decrement.
 */
@Service
public class CommitService {

  private final JdbcLedgerRepository repository;
  private final OperationCoordinator operations;

  public CommitService(JdbcLedgerRepository repository, OperationCoordinator operations) {
    this.repository = repository;
    this.operations = operations;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public OperationResult commit(
      String reservationIdValue, String idempotencyKey, String headerKey) {
    SliceSupport.requireHeaderMatchesBody(headerKey, idempotencyKey);
    UUID reservationId = SliceSupport.requireUuidV4(reservationIdValue, "reservationId");

    String canonical = RequestHash.canonicalCommit(reservationIdValue, idempotencyKey);
    String requestHash = RequestHash.sha256Hex(canonical);

    OperationClaim claim = operations.claim("COMMIT", idempotencyKey, requestHash);
    if (claim.replayed()) {
      return claim.replay();
    }

    Map<String, Object> reservation =
        repository
            .lockReservation(reservationId)
            .orElseThrow(
                () -> new LedgerNotFoundException("reservation not found: " + reservationIdValue));
    UUID accountId = (UUID) reservation.get("account_id");
    int amount = ((Number) reservation.get("amount")).intValue();
    String statusValue = (String) reservation.get("status");
    ReservationStatus current = ReservationStatus.parse(statusValue);
    if (!current.canTransitionTo(ReservationStatus.COMMITTED)) {
      String reason = current == ReservationStatus.COMMITTED ? "committed" : "terminal";
      throw new LedgerConflictException(
          "Reservation already " + reason + ": " + reservationIdValue);
    }

    Map<String, Object> capacity =
        repository
            .lockCapacity(accountId)
            .orElseThrow(() -> new LedgerNotFoundException("account not found for reservation"));
    int total = ((Number) capacity.get("total")).intValue();
    int reserved = ((Number) capacity.get("reserved")).intValue();
    int committed = ((Number) capacity.get("committed")).intValue();
    int available = ((Number) capacity.get("available")).intValue();

    UUID operationId = claim.operationId();
    UUID outboxId = UUID.randomUUID();

    String responseBody =
        "{\"accountId\":\""
            + accountId
            + "\",\"amount\":"
            + amount
            + ",\"idempotencyKey\":\""
            + RequestHash.escape(idempotencyKey)
            + "\",\"reservationId\":\""
            + reservationIdValue
            + "\",\"status\":\"COMMITTED\"}";

    repository.updateReservationStatus(reservationId, "COMMITTED");
    repository.moveReservedToCommitted(accountId, amount);

    String beforeJson = ReserveService.snapshotJson(total, reserved, committed, available);
    String afterJson =
        ReserveService.snapshotJson(total, reserved - amount, committed + amount, available);
    repository.insertAudit(operationId, accountId, "COMMIT", amount, beforeJson, afterJson);

    String outboxPayload =
        "{\"accountId\":\""
            + accountId
            + "\",\"amount\":"
            + amount
            + ",\"operationId\":\""
            + operationId
            + "\",\"reservationId\":\""
            + reservationIdValue
            + "\"}";
    repository.insertOutbox(outboxId, "commit", outboxPayload);

    return new OperationResult(operations.complete(operationId, responseBody), false);
  }
}
