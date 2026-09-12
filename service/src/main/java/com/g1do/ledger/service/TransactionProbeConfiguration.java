package com.g1do.ledger.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Production wiring deliberately has no failure-injection behavior. */
@Configuration
public class TransactionProbeConfiguration {

  @Bean
  TransactionProbe transactionProbe() {
    return checkpoint -> {};
  }
}
