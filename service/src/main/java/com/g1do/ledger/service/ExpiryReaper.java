package com.g1do.ledger.service;

import com.g1do.ledger.infra.JdbcLedgerRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Each bounded sweep releases all locks between candidates. */
@Service
public class ExpiryReaper {
  private static final Logger LOG = LoggerFactory.getLogger(ExpiryReaper.class);
  private final JdbcLedgerRepository repository;
  private final ExpiryService expiry;
  private final ExpiryProperties properties;

  public ExpiryReaper(
      JdbcLedgerRepository repository, ExpiryService expiry, ExpiryProperties properties) {
    this.repository = repository;
    this.expiry = expiry;
    this.properties = properties;
  }

  public int sweep() {
    int expired = 0;
    for (UUID reservationId : repository.findDueReservations(properties.batchSize())) {
      try {
        if (expiry.expire(reservationId, true)) {
          expired++;
        }
      } catch (RuntimeException failure) {
        LOG.warn("Expiry deferred for reservation {}", reservationId, failure);
      }
    }
    if (expired > 0) {
      LOG.info("Expired {} reservations", expired);
    }
    return expired;
  }
}
