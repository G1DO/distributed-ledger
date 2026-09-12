package com.g1do.ledger.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class HashMismatchException extends RuntimeException {
  public HashMismatchException(String message) {
    super(message);
  }
}
