package com.g1do.ledger.service;

import java.util.UUID;

/** A new operation owned by this transaction, or the completed result of its winning peer. */
public record OperationClaim(UUID operationId, OperationResult replay) {
  public boolean replayed() {
    return replay != null;
  }
}
