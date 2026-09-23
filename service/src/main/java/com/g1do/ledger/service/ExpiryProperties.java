package com.g1do.ledger.service;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("ledger.expiry")
public record ExpiryProperties(
    @DefaultValue("1000") @Min(1) long intervalMs,
    @DefaultValue("100") @Min(1) int batchSize,
    @DefaultValue("100") @Min(1) int lockTimeoutMs) {}
