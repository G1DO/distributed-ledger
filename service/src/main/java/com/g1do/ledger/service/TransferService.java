package com.g1do.ledger.service;

import com.g1do.ledger.domain.RequestHash;
import com.g1do.ledger.infra.JdbcLedgerRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Move available capacity between two accounts with one atomic audit and outbox effect. */
@Service
public class TransferService {

  private final JdbcLedgerRepository repository;
  private final OperationCoordinator operations;

  public TransferService(JdbcLedgerRepository repository, OperationCoordinator operations) {
    this.repository = repository;
    this.operations = operations;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public OperationResult transfer(
      String fromAccountIdValue,
      String toAccountIdValue,
      int amount,
      String idempotencyKey,
      String headerKey) {
    SliceSupport.requireHeaderMatchesBody(headerKey, idempotencyKey);
    if (amount <= 0) {
      throw new BadRequestException("amount must be > 0");
    }
    UUID fromAccountId = SliceSupport.requireUuidV4(fromAccountIdValue, "fromAccountId");
    UUID toAccountId = SliceSupport.requireUuidV4(toAccountIdValue, "toAccountId");
    if (fromAccountId.equals(toAccountId)) {
      throw new BadRequestException("fromAccountId and toAccountId must differ");
    }
    String requestHash =
        RequestHash.sha256Hex(
            RequestHash.canonicalTransfer(
                fromAccountIdValue, toAccountIdValue, amount, idempotencyKey));
    OperationClaim claim = operations.claim("TRANSFER", idempotencyKey, requestHash);
    if (claim.replayed()) {
      return claim.replay();
    }

    List<Map<String, Object>> capacities = repository.lockCapacities(fromAccountId, toAccountId);
    if (capacities.size() != 2) {
      throw new LedgerNotFoundException("source or destination account not found");
    }
    boolean sourceFirst = fromAccountId.equals(capacities.get(0).get("account_id"));
    Map<String, Object> source = capacities.get(sourceFirst ? 0 : 1);
    Map<String, Object> destination = capacities.get(sourceFirst ? 1 : 0);
    if (((Number) destination.get("total")).intValue() > Integer.MAX_VALUE - amount) {
      throw new BadRequestException("destination total would exceed 2147483647");
    }
    if (((Number) source.get("available")).intValue() < amount) {
      throw new OverCapacityException("Transfer over source capacity");
    }

    UUID operationId = claim.operationId();
    repository.addTotal(fromAccountId, -amount);
    repository.addTotal(toAccountId, amount);

    String beforeJson =
        "{\"from\":"
            + snapshotJson(fromAccountId, source, 0)
            + ",\"to\":"
            + snapshotJson(toAccountId, destination, 0)
            + "}";
    String afterJson =
        "{\"from\":"
            + snapshotJson(fromAccountId, source, -amount)
            + ",\"to\":"
            + snapshotJson(toAccountId, destination, amount)
            + "}";
    repository.insertAudit(operationId, fromAccountId, "TRANSFER", amount, beforeJson, afterJson);

    String outboxPayload =
        "{\"amount\":"
            + amount
            + ",\"fromAccountId\":\""
            + fromAccountId
            + "\",\"operationId\":\""
            + operationId
            + "\",\"toAccountId\":\""
            + toAccountId
            + "\"}";
    repository.insertOutbox(UUID.randomUUID(), "transfer", outboxPayload);

    String responseBody =
        "{\"amount\":"
            + amount
            + ",\"fromAccountId\":\""
            + RequestHash.escape(fromAccountIdValue)
            + "\",\"idempotencyKey\":\""
            + RequestHash.escape(idempotencyKey)
            + "\",\"toAccountId\":\""
            + RequestHash.escape(toAccountIdValue)
            + "\",\"status\":\"TRANSFERRED\"}";
    return new OperationResult(operations.complete(operationId, responseBody), false);
  }

  private static String snapshotJson(UUID accountId, Map<String, Object> capacity, int delta) {
    return "{\"accountId\":\""
        + accountId
        + "\",\"available\":"
        + (((Number) capacity.get("available")).intValue() + delta)
        + ",\"committed\":"
        + capacity.get("committed")
        + ",\"reserved\":"
        + capacity.get("reserved")
        + ",\"total\":"
        + (((Number) capacity.get("total")).intValue() + delta)
        + "}";
  }
}
