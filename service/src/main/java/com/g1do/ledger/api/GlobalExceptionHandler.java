package com.g1do.ledger.api;

import com.g1do.ledger.service.BadRequestException;
import com.g1do.ledger.service.HashMismatchException;
import com.g1do.ledger.service.LedgerConflictException;
import com.g1do.ledger.service.LedgerNotFoundException;
import com.g1do.ledger.service.OverCapacityException;
import com.g1do.ledger.service.SliceSupport;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps slice failures to stable HTTP codes: 400 validation, 404 missing, 409 conflict, 422 hash.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(BadRequestException.class)
  public ResponseEntity<Map<String, String>> badRequest(BadRequestException failure) {
    return error(HttpStatus.BAD_REQUEST, "bad_request", failure.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, String>> invalidBody(MethodArgumentNotValidException failure) {
    return error(HttpStatus.BAD_REQUEST, "bad_request", "Validation failed");
  }

  @ExceptionHandler(MissingRequestHeaderException.class)
  public ResponseEntity<Map<String, String>> missingHeader(MissingRequestHeaderException failure) {
    return error(HttpStatus.BAD_REQUEST, "bad_request", "Missing Idempotency-Key header");
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<Map<String, String>> missingParam(
      MissingServletRequestParameterException failure) {
    return error(HttpStatus.BAD_REQUEST, "bad_request", "Missing required parameter");
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, String>> unreadableBody(
      HttpMessageNotReadableException failure) {
    return error(HttpStatus.BAD_REQUEST, "bad_request", "Malformed JSON body");
  }

  @ExceptionHandler(LedgerNotFoundException.class)
  public ResponseEntity<Map<String, String>> notFound(LedgerNotFoundException failure) {
    return error(HttpStatus.NOT_FOUND, "not_found", failure.getMessage());
  }

  @ExceptionHandler({OverCapacityException.class, LedgerConflictException.class})
  public ResponseEntity<Map<String, String>> conflict(RuntimeException failure) {
    return error(HttpStatus.CONFLICT, "conflict", failure.getMessage());
  }

  @ExceptionHandler(HashMismatchException.class)
  public ResponseEntity<Map<String, String>> hashMismatch(HashMismatchException failure) {
    return error(HttpStatus.UNPROCESSABLE_ENTITY, "idempotency_key_mismatch", failure.getMessage());
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<Map<String, String>> dataConflict(DataIntegrityViolationException failure) {
    String state = SliceSupport.sqlState(failure);
    if ("23514".equals(state)) {
      return error(HttpStatus.CONFLICT, "conflict", "Capacity violated");
    }
    if ("23505".equals(state)) {
      return error(HttpStatus.CONFLICT, "conflict", "Idempotency-Key already used");
    }
    return error(HttpStatus.CONFLICT, "conflict", "State conflict");
  }

  private ResponseEntity<Map<String, String>> error(
      HttpStatus status, String code, String message) {
    return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
  }
}
