package com.g1do.ledger.app;

import com.g1do.ledger.service.ExpiryReaper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "ledger.expiry.enabled", havingValue = "true", matchIfMissing = true)
public class ExpiryScheduler {
  private final ExpiryReaper reaper;

  public ExpiryScheduler(ExpiryReaper reaper) {
    this.reaper = reaper;
  }

  @Scheduled(
      fixedDelayString = "${ledger.expiry.interval-ms:1000}",
      initialDelayString = "${ledger.expiry.interval-ms:1000}")
  public void sweep() {
    reaper.sweep();
  }
}
