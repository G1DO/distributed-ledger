package com.g1do.ledger.api;

import com.g1do.ledger.api.dto.ReserveRequest;
import com.g1do.ledger.service.OperationResult;
import com.g1do.ledger.service.ReserveService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class ReserveController {

  private final ReserveService reserveService;

  public ReserveController(ReserveService reserveService) {
    this.reserveService = reserveService;
  }

  @PostMapping(
      value = "/reserve",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> reserve(
      @RequestHeader("Idempotency-Key") String headerKey, @Valid @RequestBody ReserveRequest body) {
    OperationResult result =
        reserveService.reserve(
            body.accountId(), body.amount(), body.idempotencyKey(), body.ttlSec(), headerKey);
    return ResponseEntity.status(result.replayed() ? 200 : 201)
        .contentType(MediaType.APPLICATION_JSON)
        .body(result.responseBody());
  }
}
