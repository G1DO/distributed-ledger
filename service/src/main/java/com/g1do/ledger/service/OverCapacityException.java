package com.g1do.ledger.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class OverCapacityException extends RuntimeException {
  public OverCapacityException(String message) {
    super(message);
  }
}
