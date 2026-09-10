package com.g1do.ledger.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.g1do.ledger")
public class LedgerApplication {
  public static void main(String[] args) {
    SpringApplication.run(LedgerApplication.class, args);
  }
}
