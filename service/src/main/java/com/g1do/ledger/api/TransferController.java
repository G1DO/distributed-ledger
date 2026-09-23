package com.g1do.ledger.api;

import com.g1do.ledger.api.dto.TransferRequest;
import com.g1do.ledger.service.OperationResult;
import com.g1do.ledger.service.TransferService;
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
public class TransferController {

  private final TransferService transferService;

  public TransferController(TransferService transferService) {
    this.transferService = transferService;
  }

  @PostMapping(
      value = "/transfer",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> transfer(
      @RequestHeader("Idempotency-Key") String headerKey,
      @Valid @RequestBody TransferRequest body) {
    OperationResult result =
        transferService.transfer(
            body.fromAccountId(),
            body.toAccountId(),
            body.amount(),
            body.idempotencyKey(),
            headerKey);
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(result.responseBody());
  }
}
