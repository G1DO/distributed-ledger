package com.g1do.ledger.app;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AsyncConfig {

  @Bean(destroyMethod = "shutdown")
  public ExecutorService ledgerExecutor() {
    return Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
  }
}
