package com.g1do.ledger.service;

import com.g1do.ledger.infra.JdbcLedgerRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Capacity reads enforce expiry; operation lookup remains an immutable stored-response read. */
@Service
public class QueryService {

  private final JdbcLedgerRepository repository;
  private final ExpiryService expiry;

  public QueryService(JdbcLedgerRepository repository, ExpiryService expiry) {
    this.repository = repository;
    this.expiry = expiry;
  }

  public Map<String, Object> capacityByAccount(String accountIdValue) {
    UUID accountId = SliceSupport.requireUuidV4(accountIdValue, "accountId");
    expiry.expireForAccount(accountId);
    return repository.getCapacity(accountId);
  }

  @Transactional(readOnly = true)
  public String responseBodyByKey(String key) {
    return repository
        .findOperationByKey(key)
        .map(row -> (String) row.get("response_body"))
        .orElseThrow(() -> new LedgerNotFoundException("operation not found: " + key));
  }
}
