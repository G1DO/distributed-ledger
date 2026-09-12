package com.g1do.ledger.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReserveRequest(
    @NotBlank String accountId,
    @Min(1) int amount,
    @NotBlank @Size(min = 1, max = 64) String idempotencyKey,
    @Min(1) Integer ttlSec) {}
