package com.g1do.ledger.app;

import com.g1do.ledger.service.ExpiryProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(scanBasePackages = "com.g1do.ledger")
@EnableConfigurationProperties(ExpiryProperties.class)
public class LedgerApplication {
  public static void main(String[] args) {
    SpringApplication.run(LedgerApplication.class, args);
  }
}
