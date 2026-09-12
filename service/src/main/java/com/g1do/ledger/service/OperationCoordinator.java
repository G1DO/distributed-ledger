package com.g1do.ledger.service;

import com.g1do.ledger.infra.JdbcLedgerRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claim operation identity before taking business locks; no claim survives a failed transaction.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class OperationCoordinator {
  private final JdbcLedgerRepository repository;

  public OperationCoordinator(JdbcLedgerRepository repository) {
    this.repository = repository;
  }

  public OperationClaim claim(String type, String key, String requestHash) {
    UUID id = UUID.randomUUID();
    if (repository.claimOperation(id, key, type, requestHash)) {
      return new OperationClaim(id, null);
    }
    // ON CONFLICT waits for the winner. This new READ COMMITTED statement sees its completed row.
    Map<String, Object> winner = repository.findOperationByKey(key).orElseThrow();
    if (!type.equals(winner.get("type")) || !requestHash.equals(winner.get("request_hash"))) {
      throw new HashMismatchException("Idempotency-Key reuse with different body");
    }
    if (!"COMPLETED".equals(winner.get("status")) || winner.get("response_body") == null) {
      throw new IllegalStateException("An operation claim was committed without its response");
    }
    return new OperationClaim(
        (UUID) winner.get("id"), new OperationResult((String) winner.get("response_body"), true));
  }

  public String complete(UUID id, String responseBody) {
    repository.completeOperation(id, responseBody);
    return repository.getOperationResponseBody(id);
  }
}
