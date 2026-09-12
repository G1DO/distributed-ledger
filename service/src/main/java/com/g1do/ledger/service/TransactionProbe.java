package com.g1do.ledger.service;

/**
 * Test seam for simulating a request failure at a transaction boundary. Production uses the no-op
 * implementation registered in {@link TransactionProbeConfiguration}.
 */
@FunctionalInterface
public interface TransactionProbe {
  void reached(TransactionCheckpoint checkpoint);
}
