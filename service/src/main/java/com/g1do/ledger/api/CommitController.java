package com.g1do.ledger.api;

import com.g1do.ledger.api.dto.CommitRequest;
import com.g1do.ledger.domain.RequestHash;
import com.g1do.ledger.service.CommitService;
import com.g1do.ledger.service.HashMismatchException;
import com.g1do.ledger.service.OperationResult;
import com.g1do.ledger.service.OverCapacityException;
import com.g1do.ledger.service.QueryService;
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
public class CommitController {

  private final CommitService commitService;
  private final QueryService queryService;

  public CommitController(CommitService commitService, QueryService queryService) {
    this.commitService = commitService;
    this.queryService = queryService;
  }

  @PostMapping(
      value = "/commit",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> commit(
      @RequestHeader("Idempotency-Key") String headerKey, @Valid @RequestBody CommitRequest body) {
    try {
      OperationResult result =
          commitService.commit(body.reservationId(), body.idempotencyKey(), headerKey);
      return ResponseEntity.ok()
          .contentType(MediaType.APPLICATION_JSON)
          .body(result.responseBody());
    } catch (DataIntegrityViolationException failure) {
      String state = SliceSupport.sqlState(failure);
      if ("23505".equals(state)) {
        String canonical = RequestHash.canonicalCommit(body.reservationId(), body.idempotencyKey());
        String hash = RequestHash.sha256Hex(canonical);
        String storedHash = queryService.requestHashByKey(body.idempotencyKey());
        if (!hash.equals(storedHash)) {
          throw new HashMismatchException("Idempotency-Key reuse with different body");
        }
        String storedBody = queryService.responseBodyByKey(body.idempotencyKey());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(storedBody);
      }
      if ("23514".equals(state)) {
        throw new OverCapacityException("Commit violates capacity");
      }
      throw failure;
    }
  }
}
