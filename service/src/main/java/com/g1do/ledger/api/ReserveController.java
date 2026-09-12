package com.g1do.ledger.api;

import com.g1do.ledger.api.dto.ReserveRequest;
import com.g1do.ledger.domain.RequestHash;
import com.g1do.ledger.service.HashMismatchException;
import com.g1do.ledger.service.OperationResult;
import com.g1do.ledger.service.OverCapacityException;
import com.g1do.ledger.service.QueryService;
import com.g1do.ledger.service.ReserveService;
import com.g1do.ledger.service.SliceSupport;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
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
  private final QueryService queryService;

  public ReserveController(ReserveService reserveService, QueryService queryService) {
    this.reserveService = reserveService;
    this.queryService = queryService;
  }

  @PostMapping(
      value = "/reserve",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> reserve(
      @RequestHeader("Idempotency-Key") String headerKey, @Valid @RequestBody ReserveRequest body) {
    try {
      OperationResult result =
          reserveService.reserve(
              body.accountId(), body.amount(), body.idempotencyKey(), body.ttlSec(), headerKey);
      int status = result.replayed() ? 200 : 201;
      return ResponseEntity.status(status)
          .contentType(MediaType.APPLICATION_JSON)
          .body(result.responseBody());
    } catch (DataIntegrityViolationException failure) {
      String state = SliceSupport.sqlState(failure);
      if ("23505".equals(state)) {
        String canonical =
            RequestHash.canonicalReserve(
                body.accountId(), body.amount(), body.idempotencyKey(), body.ttlSec());
        String hash = RequestHash.sha256Hex(canonical);
        String storedHash = queryService.requestHashByKey(body.idempotencyKey());
        if (!hash.equals(storedHash)) {
          throw new HashMismatchException("Idempotency-Key reuse with different body");
        }
        String storedBody = queryService.responseBodyByKey(body.idempotencyKey());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(storedBody);
      }
      if ("23514".equals(state)) {
        throw new OverCapacityException("Reserve over capacity");
      }
      throw failure;
    }
  }
}
