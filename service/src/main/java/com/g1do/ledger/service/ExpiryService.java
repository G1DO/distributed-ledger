package com.g1do.ledger.service;

import com.g1do.ledger.domain.RequestHash;
import com.g1do.ledger.infra.JdbcLedgerRepository;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Expiry owns its transaction so a rejected user command cannot roll back the expiry effect. */
@Service
public class ExpiryService {
  private final JdbcLedgerRepository repository;
  private final OperationCoordinator operations;
  private final ExpiryProperties properties;
  private final TransactionTemplate commands;
  private final TransactionTemplate expirations;

  public ExpiryService(
      JdbcLedgerRepository repository,
      OperationCoordinator operations,
      ExpiryProperties properties,
      PlatformTransactionManager transactionManager) {
    this.repository = repository;
    this.operations = operations;
    this.properties = properties;
    commands = new TransactionTemplate(transactionManager);
    commands.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    expirations = new TransactionTemplate(transactionManager);
    expirations.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    expirations.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public OperationResult executeTerminal(UUID reservationId, Supplier<OperationResult> command) {
    try {
      return commands.execute(transaction -> command.get());
    } catch (ExpiryDueException due) {
      // The failed command's claim and locks are gone before acquiring expiry's locks.
      expire(reservationId, false);
      throw new LedgerConflictException("Reservation expired: " + reservationId);
    }
  }

  public void rejectIfDue(Map<String, Object> reservation) {
    if (isDue(reservation)) {
      throw new ExpiryDueException();
    }
  }

  public void expireForAccount(UUID accountId) {
    for (UUID reservationId : repository.findDueReservationsForAccount(accountId)) {
      expire(reservationId, false);
    }
  }

  /** One claim -> reservation -> capacity transaction, with no retained claim on a no-op. */
  public boolean expire(UUID reservationId, boolean skipLocked) {
    return Boolean.TRUE.equals(
        expirations.execute(
            transaction -> {
              if (skipLocked) {
                repository.setLocalLockTimeout(properties.lockTimeoutMs());
              }
              // Longer than the public 64-character key limit; clients cannot claim this key.
              String key = "internal-expire:" + reservationId + ":" + UUID.randomUUID();
              OperationClaim claim =
                  operations.claim("EXPIRE", key, RequestHash.sha256Hex(reservationId.toString()));
              Map<String, Object> reservation =
                  repository.lockReservation(reservationId, skipLocked).orElse(null);
              if (reservation == null || !isDue(reservation)) {
                transaction.setRollbackOnly();
                return false;
              }
              UUID accountId = (UUID) reservation.get("account_id");
              int amount = ((Number) reservation.get("amount")).intValue();
              Map<String, Object> capacity =
                  repository
                      .lockCapacity(accountId)
                      .orElseThrow(
                          () -> new LedgerNotFoundException("account not found for reservation"));
              int total = ((Number) capacity.get("total")).intValue();
              int reserved = ((Number) capacity.get("reserved")).intValue();
              int committed = ((Number) capacity.get("committed")).intValue();
              int available = ((Number) capacity.get("available")).intValue();
              repository.updateReservationStatus(reservationId, "EXPIRED");
              repository.releaseReserved(accountId, amount);
              repository.insertAudit(
                  claim.operationId(),
                  accountId,
                  "EXPIRE",
                  amount,
                  ReserveService.snapshotJson(total, reserved, committed, available),
                  ReserveService.snapshotJson(
                      total, reserved - amount, committed, available + amount));
              String payload =
                  "{\"accountId\":\""
                      + accountId
                      + "\",\"amount\":"
                      + amount
                      + ",\"operationId\":\""
                      + claim.operationId()
                      + "\",\"reservationId\":\""
                      + reservationId
                      + "\",\"status\":\"EXPIRED\"}";
              repository.insertOutbox(UUID.randomUUID(), "reservation", payload);
              operations.complete(claim.operationId(), payload);
              return true;
            }));
  }

  private static boolean isDue(Map<String, Object> reservation) {
    return "RESERVED".equals(reservation.get("status"))
        && Boolean.TRUE.equals(reservation.get("due"));
  }

  private static final class ExpiryDueException extends RuntimeException {}
}
