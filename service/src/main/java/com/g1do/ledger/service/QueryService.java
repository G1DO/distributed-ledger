package com.g1do.ledger.service;

import com.g1do.ledger.infra.JdbcLedgerRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only queries for the O1 slice. */
@Service
public class QueryService {

  private final JdbcLedgerRepository repository;

  public QueryService(JdbcLedgerRepository repository) {
    this.repository = repository;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> capacityByAccount(String accountIdValue) {
    UUID accountId = SliceSupport.requireUuidV4(accountIdValue, "accountId");
    return repository.getCapacity(accountId);
  }

  @Transactional(readOnly = true)
  public String responseBodyByKey(String key) {
    return repository
        .findOperationByKey(key)
        .map(row -> (String) row.get("response_body"))
        .orElseThrow(() -> new LedgerNotFoundException("operation not found: " + key));
  }

  @Transactional(readOnly = true)
  public String requestHashByKey(String key) {
    return repository
        .findOperationByKey(key)
        .map(row -> (String) row.get("request_hash"))
        .orElseThrow(() -> new LedgerNotFoundException("operation not found: " + key));
  }
}
