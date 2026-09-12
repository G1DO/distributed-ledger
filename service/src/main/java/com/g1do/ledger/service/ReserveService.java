package com.g1do.ledger.service;

import com.g1do.ledger.domain.RequestHash;
import com.g1do.ledger.infra.JdbcLedgerRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reserve slice: single transaction inserts operation + reservation + outbox + exactly one audit
 * row. Concurrency control is {@code SELECT ... FOR UPDATE} on the capacity row (documented in
 * DEC-LEDGER-04); DB {@code CHECK(available>=0)} is the backstop and maps to 409.
 */
@Service
public class ReserveService {

  private final JdbcLedgerRepository repository;
  private final TransactionProbe transactionProbe;

  public ReserveService(JdbcLedgerRepository repository, TransactionProbe transactionProbe) {
    this.repository = repository;
    this.transactionProbe = transactionProbe;
  }

  @Transactional
  public OperationResult reserve(
      String accountIdValue, int amount, String idempotencyKey, Integer ttlSec, String headerKey) {
    SliceSupport.requireHeaderMatchesBody(headerKey, idempotencyKey);
    if (amount <= 0) {
      throw new BadRequestException("amount must be > 0");
    }
    UUID accountId = SliceSupport.requireUuidV4(accountIdValue, "accountId");

    String canonical = RequestHash.canonicalReserve(accountIdValue, amount, idempotencyKey, ttlSec);
    String requestHash = RequestHash.sha256Hex(canonical);

    Optional<Map<String, Object>> existing = repository.findOperationByKey(idempotencyKey);
    if (existing.isPresent()) {
      String storedHash = (String) existing.get().get("request_hash");
      String storedBody = (String) existing.get().get("response_body");
      if (!requestHash.equals(storedHash)) {
        throw new HashMismatchException("Idempotency-Key reuse with different body");
      }
      return new OperationResult(storedBody, true);
    }

    Map<String, Object> capacity =
        repository
            .lockCapacity(accountId)
            .orElseThrow(() -> new LedgerNotFoundException("account not found: " + accountIdValue));
    transactionProbe.reached(TransactionCheckpoint.AFTER_CAPACITY_LOCK);
    int total = ((Number) capacity.get("total")).intValue();
    int reserved = ((Number) capacity.get("reserved")).intValue();
    int committed = ((Number) capacity.get("committed")).intValue();
    int available = ((Number) capacity.get("available")).intValue();
    if (available < amount) {
      throw new OverCapacityException("Reserve over capacity");
    }

    UUID operationId = UUID.randomUUID();
    UUID reservationId = UUID.randomUUID();
    UUID outboxId = UUID.randomUUID();

    String responseBody =
        "{\"accountId\":\""
            + RequestHash.escape(accountIdValue)
            + "\",\"amount\":"
            + amount
            + ",\"idempotencyKey\":\""
            + RequestHash.escape(idempotencyKey)
            + "\",\"reservationId\":\""
            + reservationId
            + "\",\"status\":\"RESERVED\"}";

    repository.insertOperation(
        operationId, idempotencyKey, "RESERVE", "COMPLETED", requestHash, responseBody);
    String storedBody = repository.getOperationResponseBody(operationId);
    transactionProbe.reached(TransactionCheckpoint.AFTER_OPERATION_INSERT);
    repository.addReserved(accountId, amount);
    repository.insertReservation(reservationId, accountId, operationId, amount, "RESERVED");
    transactionProbe.reached(TransactionCheckpoint.AFTER_RESERVATION_INSERT);

    String beforeJson = snapshotJson(total, reserved, committed, available);
    String afterJson = snapshotJson(total, reserved + amount, committed, available - amount);
    repository.insertAudit(operationId, accountId, "RESERVE", amount, beforeJson, afterJson);

    String outboxPayload =
        "{\"accountId\":\""
            + RequestHash.escape(accountIdValue)
            + "\",\"amount\":"
            + amount
            + ",\"operationId\":\""
            + operationId
            + "\",\"reservationId\":\""
            + reservationId
            + "\"}";
    repository.insertOutbox(outboxId, "reservation", outboxPayload);
    transactionProbe.reached(TransactionCheckpoint.AFTER_OUTBOX_INSERT);

    return new OperationResult(storedBody, false);
  }

  static String snapshotJson(int total, int reserved, int committed, int available) {
    return "{\"available\":"
        + available
        + ",\"committed\":"
        + committed
        + ",\"reserved\":"
        + reserved
        + ",\"total\":"
        + total
        + "}";
  }
}
