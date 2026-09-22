package com.g1do.ledger.service;

import com.g1do.ledger.domain.RequestHash;
import com.g1do.ledger.domain.ReservationStatus;
import com.g1do.ledger.infra.JdbcLedgerRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Release reserved capacity with one atomic operation, audit, and outbox effect. */
@Service
public class ReleaseService {

  private final JdbcLedgerRepository repository;
  private final OperationCoordinator operations;

  public ReleaseService(JdbcLedgerRepository repository, OperationCoordinator operations) {
    this.repository = repository;
    this.operations = operations;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public OperationResult release(
      String reservationIdValue, String idempotencyKey, String headerKey) {
    SliceSupport.requireHeaderMatchesBody(headerKey, idempotencyKey);
    UUID reservationId = SliceSupport.requireUuidV4(reservationIdValue, "reservationId");
    String requestHash =
        RequestHash.sha256Hex(RequestHash.canonicalRelease(reservationIdValue, idempotencyKey));

    OperationClaim claim = operations.claim("RELEASE", idempotencyKey, requestHash);
    if (claim.replayed()) {
      return claim.replay();
    }

    Map<String, Object> reservation =
        repository
            .lockReservation(reservationId)
            .orElseThrow(
                () -> new LedgerNotFoundException("reservation not found: " + reservationIdValue));
    ReservationStatus current = ReservationStatus.parse((String) reservation.get("status"));
    if (!current.canTransitionTo(ReservationStatus.RELEASED)) {
      throw new LedgerConflictException("Reservation already terminal: " + reservationIdValue);
    }
    UUID accountId = (UUID) reservation.get("account_id");
    int amount = ((Number) reservation.get("amount")).intValue();

    Map<String, Object> capacity =
        repository
            .lockCapacity(accountId)
            .orElseThrow(() -> new LedgerNotFoundException("account not found for reservation"));
    int total = ((Number) capacity.get("total")).intValue();
    int reserved = ((Number) capacity.get("reserved")).intValue();
    int committed = ((Number) capacity.get("committed")).intValue();
    int available = ((Number) capacity.get("available")).intValue();

    UUID operationId = claim.operationId();
    String responseBody =
        "{\"accountId\":\""
            + accountId
            + "\",\"amount\":"
            + amount
            + ",\"idempotencyKey\":\""
            + RequestHash.escape(idempotencyKey)
            + "\",\"reservationId\":\""
            + reservationIdValue
            + "\",\"status\":\"RELEASED\"}";

    repository.updateReservationStatus(reservationId, "RELEASED");
    repository.releaseReserved(accountId, amount);

    String beforeJson = ReserveService.snapshotJson(total, reserved, committed, available);
    String afterJson =
        ReserveService.snapshotJson(total, reserved - amount, committed, available + amount);
    repository.insertAudit(operationId, accountId, "RELEASE", amount, beforeJson, afterJson);

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
    repository.insertOutbox(UUID.randomUUID(), "release", outboxPayload);

    return new OperationResult(operations.complete(operationId, responseBody), false);
  }
}
