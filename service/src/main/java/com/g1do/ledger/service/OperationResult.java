package com.g1do.ledger.service;

/** Idempotent operation outcome: stored response body plus whether it was a replay. */
public record OperationResult(String responseBody, boolean replayed) {}
