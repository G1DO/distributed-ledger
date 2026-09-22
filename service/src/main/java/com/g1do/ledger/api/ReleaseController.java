package com.g1do.ledger.api;

import com.g1do.ledger.api.dto.ReleaseRequest;
import com.g1do.ledger.service.OperationResult;
import com.g1do.ledger.service.ReleaseService;
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
public class ReleaseController {

  private final ReleaseService releaseService;

  public ReleaseController(ReleaseService releaseService) {
    this.releaseService = releaseService;
  }

  @PostMapping(
      value = "/release",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> release(
      @RequestHeader("Idempotency-Key") String headerKey, @Valid @RequestBody ReleaseRequest body) {
    OperationResult result =
        releaseService.release(body.reservationId(), body.idempotencyKey(), headerKey);
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(result.responseBody());
  }
}
