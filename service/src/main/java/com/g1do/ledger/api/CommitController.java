package com.g1do.ledger.api;

import com.g1do.ledger.api.dto.CommitRequest;
import com.g1do.ledger.service.CommitService;
import com.g1do.ledger.service.OperationResult;
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
public class CommitController {

  private final CommitService commitService;

  public CommitController(CommitService commitService) {
    this.commitService = commitService;
  }

  @PostMapping(
      value = "/commit",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> commit(
      @RequestHeader("Idempotency-Key") String headerKey, @Valid @RequestBody CommitRequest body) {
    OperationResult result =
        commitService.commit(body.reservationId(), body.idempotencyKey(), headerKey);
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(result.responseBody());
  }
}
