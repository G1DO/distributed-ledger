package com.g1do.ledger.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class LedgerNotFoundException extends RuntimeException {
  public LedgerNotFoundException(String message) {
    super(message);
  }
}
