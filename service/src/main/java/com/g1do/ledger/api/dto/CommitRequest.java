package com.g1do.ledger.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CommitRequest(
    @NotBlank String reservationId, @NotBlank @Size(min = 1, max = 64) String idempotencyKey) {}
