package com.g1do.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.g1do.ledger.infra.JdbcLedgerRepository;
import com.g1do.ledger.service.BadRequestException;
import com.g1do.ledger.service.ExpiryService;
import com.g1do.ledger.service.ReserveService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class LazyExpiryIT extends PostgresITBase {
  @Autowired private ReserveService reserveService;
  @Autowired private ExpiryService expiry;
  @Autowired private JdbcLedgerRepository repository;
  @Autowired private PlatformTransactionManager transactionManager;

  @ParameterizedTest
  @ValueSource(ints = {1, Integer.MAX_VALUE})
  void deadlineUsesDatabaseTransactionCreationTimeAndEqualityIsExpired(int ttl) {
    UUID account = account();
    UUID reservation =
        new TransactionTemplate(transactionManager)
            .execute(
                transaction -> {
                  UUID id = reserve(account, ttl, key());
                  assertThat(
                          jdbc.queryForObject(
                              "SELECT expires_at = now() + ? * interval '1 second' FROM reservation WHERE id = ?",
                              Boolean.class,
                              ttl,
                              id))
                      .isTrue();
                  jdbc.update("UPDATE reservation SET expires_at = now() WHERE id = ?", id);
                  assertThat(repository.lockReservation(id).orElseThrow())
                      .containsEntry("due", true);
                  return id;
                });
    assertThat(expiry.expire(reservation, false)).isTrue();
    assertExpired(account, reservation);
  }

  @ParameterizedTest
  @ValueSource(strings = {"commit", "release"})
  void expiredCommandPersistsExpiryReturnsConflictAndDoesNotRetainClientKey(String command)
      throws Exception {
    UUID account = account();
    UUID reservation = reserve(account, 3600, key());
    overdue(reservation);
    String key = key();
    command(command, reservation, key).andExpect(status().isConflict());
    assertExpired(account, reservation);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM operation WHERE idempotency_key = ?", Integer.class, key))
        .isZero();
    command(command, reservation, key).andExpect(status().isConflict());
    assertExpired(account, reservation);
    // A failed command leaves its key available for a different valid request.
    UUID replacement = reserve(account, null, key());
    command(command, replacement, key).andExpect(status().isOk());
  }

  @Test
  void capacityReadExpiresOnlyAddressedAccountAndKeepsNullAndFutureReservations() throws Exception {
    UUID account = account();
    UUID otherAccount = account();
    UUID first = reserve(account, 3600, key());
    UUID second = reserve(account, 3600, key());
    UUID forever = reserve(account, null, key());
    UUID future = reserve(account, Integer.MAX_VALUE, key());
    UUID other = reserve(otherAccount, 3600, key());
    overdue(first);
    overdue(second);
    overdue(other);
    for (int i = 0; i < 2; i++) {
      mockMvc
          .perform(get("/v1/query").param("accountId", account.toString()))
          .andExpect(status().isOk());
      new InvariantChecker(jdbc, account).assertExpectedCapacity(account, 100, 20, 0);
      assertThat(state(first)).isEqualTo("EXPIRED");
      assertThat(state(second)).isEqualTo("EXPIRED");
      assertThat(state(forever)).isEqualTo("RESERVED");
      assertThat(state(future)).isEqualTo("RESERVED");
      assertThat(state(other)).isEqualTo("RESERVED");
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_entry WHERE account_id = ? AND kind = 'EXPIRE'",
                Integer.class,
                account))
        .isEqualTo(2);
    mockMvc
        .perform(get("/v1/query").param("accountId", otherAccount.toString()))
        .andExpect(status().isOk());
    assertExpired(otherAccount, other);
  }

  @Test
  void reserveReplayAndOperationLookupKeepOriginalResponseAndDeadlineAfterExpiry()
      throws Exception {
    UUID account = account();
    String key = key();
    String original = reserveService.reserve(account.toString(), 10, key, 3600, key).responseBody();
    UUID reservation = reservation(key);
    overdue(reservation);
    Object deadline =
        jdbc.queryForMap("SELECT expires_at FROM reservation WHERE id = ?", reservation)
            .get("expires_at");
    assertThat(
            mockMvc
                .perform(get("/v1/operations/{key}", key))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .isEqualTo(original);
    assertThat(state(reservation)).isEqualTo("RESERVED");
    mockMvc
        .perform(get("/v1/query").param("accountId", account.toString()))
        .andExpect(status().isOk());
    assertThat(reserveService.reserve(account.toString(), 10, key, 3600, key).responseBody())
        .isEqualTo(original);
    assertThat(
            mockMvc
                .perform(get("/v1/operations/{key}", key))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .isEqualTo(original);
    assertThat(jdbc.queryForMap("SELECT expires_at FROM reservation WHERE id = ?", reservation))
        .containsEntry("expires_at", deadline);
    assertExpired(account, reservation);
  }

  @Test
  void mismatchedKeyDoesNotLazilyExpireTheTarget() throws Exception {
    UUID account = account();
    String reserveKey = key();
    UUID reservation = reserve(account, 3600, reserveKey);
    overdue(reservation);
    command("commit", reservation, reserveKey).andExpect(status().isUnprocessableContent());
    command("release", reservation, reserveKey).andExpect(status().isUnprocessableContent());
    assertThat(state(reservation)).isEqualTo("RESERVED");
    assertThatThrownBy(
            () -> reserveService.reserve(account.toString(), 10, reserveKey, 1, reserveKey))
        .isInstanceOf(com.g1do.ledger.service.HashMismatchException.class);
    assertThat(expiry.expire(reservation, false)).isTrue();
    assertExpired(account, reservation);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void invalidTtlIsRejectedAtHttpAndServiceBoundaries(int ttl) throws Exception {
    UUID account = account();
    String key = key();
    mockMvc
        .perform(
            post("/v1/reserve")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"accountId\":\""
                        + account
                        + "\",\"amount\":10,\"idempotencyKey\":\""
                        + key
                        + "\",\"ttlSec\":"
                        + ttl
                        + "}"))
        .andExpect(status().isBadRequest());
    assertThatThrownBy(() -> reserveService.reserve(account.toString(), 10, key, ttl, key))
        .isInstanceOf(BadRequestException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM operation WHERE idempotency_key = ?", Integer.class, key))
        .isZero();
    new InvariantChecker(jdbc, account).assertExpectedCapacity(account, 100, 0, 0);
  }

  private UUID account() {
    UUID account = UUID.randomUUID();
    jdbc.update("INSERT INTO account (id, display_name) VALUES (?, 'lazy-expiry')", account);
    jdbc.update("INSERT INTO capacity (account_id, total) VALUES (?, 100)", account);
    return account;
  }

  private UUID reserve(UUID account, Integer ttl, String key) {
    reserveService.reserve(account.toString(), 10, key, ttl, key);
    return reservation(key);
  }

  private UUID reservation(String key) {
    return jdbc.queryForObject(
        "SELECT r.id FROM reservation r JOIN operation o ON o.id = r.operation_id"
            + " WHERE o.idempotency_key = ?",
        UUID.class,
        key);
  }

  private void overdue(UUID reservation) {
    jdbc.update(
        "UPDATE reservation SET expires_at = now() - interval '1 second' WHERE id = ?",
        reservation);
  }

  private String state(UUID reservation) {
    return jdbc.queryForObject(
        "SELECT status FROM reservation WHERE id = ?", String.class, reservation);
  }

  private void assertExpired(UUID account, UUID reservation) {
    assertThat(state(reservation)).isEqualTo("EXPIRED");
    new InvariantChecker(jdbc, account).assertExpectedCapacity(account, 100, 0, 0);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_entry WHERE account_id = ? AND kind = 'EXPIRE'",
                Integer.class,
                account))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE payload ->> 'reservationId' = ?"
                    + " AND payload ->> 'status' = 'EXPIRED'",
                Integer.class,
                reservation.toString()))
        .isEqualTo(1);
  }

  private ResultActions command(String command, UUID reservation, String key) throws Exception {
    return mockMvc.perform(
        post("/v1/" + command)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"reservationId\":\"" + reservation + "\",\"idempotencyKey\":\"" + key + "\"}"));
  }

  private static String key() {
    return "lazy-" + UUID.randomUUID();
  }
}
