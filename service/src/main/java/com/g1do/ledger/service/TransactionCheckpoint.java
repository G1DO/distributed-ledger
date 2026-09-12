package com.g1do.ledger.service;

/**
 * Named boundaries in the reserve transaction used to prove rollback behavior. The application
 * installs a no-op probe; integration tests replace it with a probe that simulates a failed
 * request. Keeping the names in production makes the crash-matrix evidence track the real write
 * order instead of a duplicate test-only transaction.
 */
public enum TransactionCheckpoint {
  AFTER_CAPACITY_LOCK,
  AFTER_OPERATION_INSERT,
  AFTER_RESERVATION_INSERT,
  AFTER_OUTBOX_INSERT
}
