package com.g1do.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.g1do.ledger.service.ReserveService;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {"ledger.expiry.enabled=true", "ledger.expiry.interval-ms=25"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExpirySchedulerIT extends PostgresITBase {
  @Autowired private ReserveService reserve;

  @Test
  void enabledSchedulerExpiresWithoutReadOrManualSweep() throws Exception {
    UUID account = UUID.randomUUID();
    jdbc.update("INSERT INTO account (id, display_name) VALUES (?, 'scheduled-expiry')", account);
    jdbc.update("INSERT INTO capacity (account_id, total) VALUES (?, 10)", account);
    String key = "scheduled-" + UUID.randomUUID();
    reserve.reserve(account.toString(), 10, key, 1, key);
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      if (Integer.valueOf(0)
          .equals(
              jdbc.queryForObject(
                  "SELECT reserved FROM capacity WHERE account_id = ?", Integer.class, account))) {
        break;
      }
      Thread.sleep(20);
    }
    new InvariantChecker(jdbc, account).assertExpectedCapacity(account, 10, 0, 0);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM reservation WHERE account_id = ?", String.class, account))
        .isEqualTo("EXPIRED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_entry WHERE account_id = ? AND kind = 'EXPIRE'",
                Integer.class,
                account))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE payload ->> 'accountId' = ?"
                    + " AND payload ->> 'status' = 'EXPIRED'",
                Integer.class,
                account.toString()))
        .isEqualTo(1);
  }
}
