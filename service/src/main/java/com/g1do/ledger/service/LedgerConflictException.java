package com.g1do.ledger.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class LedgerConflictException extends RuntimeException {
  public LedgerConflictException(String message) {
    super(message);
  }
}
