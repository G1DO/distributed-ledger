package com.g1do.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.g1do.ledger.GeneratedHistory.Batch;
import com.g1do.ledger.GeneratedHistory.Command;
import com.g1do.ledger.GeneratedHistory.Fault;
import com.g1do.ledger.GeneratedHistory.Kind;
import com.g1do.ledger.GeneratedHistoryModel.Outcome;
import com.g1do.ledger.service.CommitService;
import com.g1do.ledger.service.ExpiryService;
import com.g1do.ledger.service.HashMismatchException;
import com.g1do.ledger.service.LedgerConflictException;
import com.g1do.ledger.service.LedgerNotFoundException;
import com.g1do.ledger.service.OperationResult;
import com.g1do.ledger.service.OverCapacityException;
import com.g1do.ledger.service.QueryService;
import com.g1do.ledger.service.ReleaseService;
import com.g1do.ledger.service.ReserveService;
import com.g1do.ledger.service.TransferService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** 1,000 real PostgreSQL histories, with exhaustive ordering within each concurrent batch. */
@TestPropertySource(
    properties = "spring.datasource.hikari.connection-init-sql=SET statement_timeout = '20s'")
class O2GeneratedHistoryIT extends PostgresITBase {
  private static final UUID MISSING_RESERVATION =
      new UUID(0x0000000000004000L, 0x8000000000000000L);
  @Autowired ReserveService reserve;
  @Autowired CommitService commit;
  @Autowired ReleaseService release;
  @Autowired TransferService transfer;
  @Autowired ExpiryService expiry;
  @Autowired QueryService query;

  @Test
  void oneThousandSeededConcurrentHistories() throws Exception {
    Path directory = Path.of("target", "o2-histories");
    Files.createDirectories(directory);
    String repro = System.getProperty("ledger.history.repro");
    long firstSeed = Long.getLong("ledger.history.seed", 4_210_421L);
    int count = repro == null ? Integer.getInteger("ledger.history.count", 1000) : 1;
    assertThat(count).as("history count").isPositive();
    assertThat(Integer.getInteger("ledger.history.shrinkBudget", 100))
        .as("shrink budget")
        .isNotNegative();
    Path seeds = directory.resolve("seeds.csv");
    Files.writeString(seeds, "seed,batches,commands,result,elapsed_ms\n");
    for (int index = 0; index < count; index++) {
      long seed = firstSeed + index;
      GeneratedHistory history =
          repro == null
              ? GeneratedHistory.generate(seed)
              : GeneratedHistory.decode(Files.readString(Path.of(repro)));
      long start = System.nanoTime();
      List<String> trace = new ArrayList<>();
      try {
        runHistory(history, trace);
        appendSeed(seeds, seed, history, "PASS", start);
      } catch (AssertionError | Exception failure) {
        appendSeed(seeds, seed, history, "FAIL", start);
        preserveFailure(directory, seed, history, trace, failure);
        throw new AssertionError("History seed=" + seed + "; evidence=" + directory, failure);
      }
    }
  }

  @Test
  void unknownMismatchedRequestResolvesWithoutReplacingTheStoredWinner() throws Exception {
    runHistory(
        new GeneratedHistory(
            List.of(
                new Batch(List.of(), List.of(new Command(Kind.RESERVE, 0, 0, 10, 0, Fault.NONE))),
                new Batch(
                    List.of(),
                    List.of(new Command(Kind.RESERVE, 0, 0, 11, 0, Fault.BEFORE_DISPATCH))))),
        new ArrayList<>());
  }

  private void runHistory(GeneratedHistory history, List<String> trace) throws Exception {
    UUID[] accounts = {UUID.randomUUID(), UUID.randomUUID()};
    String prefix = "h-" + UUID.randomUUID() + "-";
    Map<Integer, UUID> reservations = new ConcurrentHashMap<>();
    Map<String, String> storedResponses = new ConcurrentHashMap<>();
    try (var pool = Executors.newFixedThreadPool(3)) {
      for (UUID account : accounts) {
        jdbc.update("INSERT INTO account (id, display_name) VALUES (?, 'o2-history')", account);
        jdbc.update("INSERT INTO capacity (account_id, total) VALUES (?, 100)", account);
      }
      GeneratedHistoryModel model = new GeneratedHistoryModel();
      InvariantChecker checker = new InvariantChecker(jdbc, accounts);
      for (int index = 0; index < history.batches().size(); index++) {
        Batch batch = history.batches().get(index);
        assertThat(batch.commands().size())
            .as("bounded concurrent batch width")
            .isLessThanOrEqualTo(3);
        trace.add("BATCH " + index + " " + batch);
        model.due(batch.due());
        for (int reference : batch.due()) {
          UUID reservation = reservations.get(reference);
          if (reservation != null) {
            jdbc.update(
                "UPDATE reservation SET expires_at = now() - interval '1 second' WHERE id = ?",
                reservation);
          }
        }
        CountDownLatch ready = new CountDownLatch(batch.commands().size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        for (Command command : batch.commands()) {
          futures.add(
              pool.submit(
                  () -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                      throw new IllegalStateException("Batch start timed out");
                    }
                    if (command.fault() == Fault.BEFORE_DISPATCH) {
                      throw new TimeoutException("Injected timeout before server dispatch");
                    }
                    Outcome outcome = execute(command, accounts, prefix, reservations);
                    if (command.fault() == Fault.AFTER_COMPLETION) {
                      throw new TimeoutException("Injected reply loss after server completion");
                    }
                    return outcome;
                  }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).as("workers reached start barrier").isTrue();
        start.countDown();
        List<Outcome> outcomes = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
          Command command = batch.commands().get(i);
          Outcome outcome;
          try {
            outcome = futures.get(i).get(10, TimeUnit.SECONDS);
          } catch (TimeoutException timeout) {
            if (command.kind() == Kind.EXPIRE) {
              // Internal expiry has no client key to replay. Await its original transaction result.
              trace.add("UNKNOWN internal expiry; awaiting original worker completion");
              outcome = futures.get(i).get(25, TimeUnit.SECONDS);
            } else {
              outcome = resolveUnknown(command, accounts, prefix, reservations, trace);
              // A timeout never grants permission to inspect a still-mutating fixture.
              try {
                futures.get(i).get(25, TimeUnit.SECONDS);
              } catch (ExecutionException failed) {
                if (!(failed.getCause() instanceof TimeoutException)) {
                  throw failed;
                }
              }
            }
          } catch (ExecutionException failed) {
            if (!(failed.getCause() instanceof TimeoutException)) {
              throw failed;
            }
            outcome = resolveUnknown(command, accounts, prefix, reservations, trace);
          }
          trace.add("OUTCOME " + command + " " + outcome);
          if (outcome.status() == 200 || outcome.status() == 201) {
            String previous = storedResponses.putIfAbsent(prefix + command.key(), outcome.body());
            if (previous != null) {
              assertThat(outcome.body()).as("REPLAY_BYTES").isEqualTo(previous);
            }
          }
          outcomes.add(outcome);
        }
        List<GeneratedHistoryModel> candidates = model.matchingOrders(batch.commands(), outcomes);
        assertThat(candidates).as("MODEL_OUTCOME: a legal serial order exists").isNotEmpty();
        int[][] actual = new int[2][];
        for (int account = 0; account < accounts.length; account++) {
          Map<String, Object> row =
              jdbc.queryForMap("SELECT * FROM capacity WHERE account_id = ?", accounts[account]);
          actual[account] =
              new int[] {
                ((Number) row.get("total")).intValue(),
                ((Number) row.get("reserved")).intValue(),
                ((Number) row.get("committed")).intValue()
              };
        }
        Map<Integer, String> actualStates = new HashMap<>();
        Map<UUID, Integer> references = new HashMap<>();
        reservations.forEach((reference, id) -> references.put(id, reference));
        List<Map<String, Object>> rows =
            jdbc.queryForList(
                "SELECT id, status FROM reservation WHERE account_id IN (?, ?)",
                accounts[0],
                accounts[1]);
        assertThat(rows)
            .as("MODEL_RESERVATIONS: no missing or extra reservation rows")
            .hasSize(reservations.size());
        for (var row : rows) {
          Integer reference = references.get((UUID) row.get("id"));
          assertThat(reference).as("MODEL_RESERVATIONS: known reservation identity").isNotNull();
          actualStates.put(reference, (String) row.get("status"));
        }
        trace.add("CAPACITY " + java.util.Arrays.deepToString(actual) + " STATES " + actualStates);
        model =
            candidates.stream()
                .filter(candidate -> matchesBoundary(candidate, actual, actualStates))
                .findFirst()
                .orElseThrow(
                    () -> new AssertionError("MODEL_BOUNDARY: accounting or terminal drift"));
        checker.assertO2Invariants(Map.of(accounts[0], 100, accounts[1], 100));
        checker.assertCompletedOperationKeys(
            prefix, model.operations.keySet().stream().map(key -> prefix + key).toList());
        List<UUID> expiryTargets = new ArrayList<>(reservations.values());
        expiryTargets.add(MISSING_RESERVATION);
        checker.assertCompletedExpiryOperations(expiryTargets, model.expirations);
      }
    } finally {
      cleanup(accounts, prefix);
    }
  }

  private Outcome execute(
      Command command, UUID[] accounts, String prefix, Map<Integer, UUID> reservations) {
    String key = prefix + command.key();
    UUID reservation = reservations.getOrDefault(command.reservation(), MISSING_RESERVATION);
    try {
      OperationResult result;
      switch (command.kind()) {
        case RESERVE -> {
          result =
              reserve.reserve(
                  accounts[command.account()].toString(), command.amount(), key, 3600, key);
          var matcher =
              Pattern.compile("\"reservationId\"\\s*:\\s*\"([^\"]+)\"")
                  .matcher(result.responseBody());
          assertThat(matcher.find()).as("reserve contains reservationId").isTrue();
          reservations.put(command.key(), UUID.fromString(matcher.group(1)));
        }
        case COMMIT -> result = commit.commit(reservation.toString(), key, key);
        case RELEASE -> result = release.release(reservation.toString(), key, key);
        case TRANSFER ->
            result =
                transfer.transfer(
                    accounts[command.account()].toString(),
                    accounts[1 - command.account()].toString(),
                    command.amount(),
                    key,
                    key);
        case EXPIRE -> {
          return new Outcome(expiry.expire(reservation, false) ? 1 : 0, false, "", false);
        }
        default -> throw new IllegalArgumentException("Unknown command " + command);
      }
      return new Outcome(
          command.kind() == Kind.RESERVE && !result.replayed() ? 201 : 200,
          result.replayed(),
          result.responseBody(),
          false);
    } catch (HashMismatchException mismatch) {
      return new Outcome(422, false, "", false);
    } catch (LedgerConflictException | OverCapacityException conflict) {
      return new Outcome(409, false, "", false);
    } catch (LedgerNotFoundException missing) {
      return new Outcome(404, false, "", false);
    }
  }

  private Outcome resolveUnknown(
      Command command,
      UUID[] accounts,
      String prefix,
      Map<Integer, UUID> reservations,
      List<String> trace) {
    trace.add("UNKNOWN " + command + "; lookup then identical-key replay");
    String lookup = null;
    try {
      lookup = query.responseBodyByKey(prefix + command.key());
    } catch (LedgerNotFoundException inFlightOrRolledBack) {
      trace.add("LOOKUP_ABSENT: outcome remains unknown until replay completes");
    }
    Outcome replay = execute(command, accounts, prefix, reservations);
    if (lookup != null) {
      assertThat(replay.status()).as("unknown key resolves to replay or mismatch").isIn(200, 422);
      if (replay.status() == 200) {
        assertThat(replay.body()).as("unknown response is lookup-identical").isEqualTo(lookup);
      } else {
        assertThat(query.responseBodyByKey(prefix + command.key()))
            .as("resolved mismatch preserves original stored response")
            .isEqualTo(lookup);
      }
    }
    return new Outcome(replay.status(), replay.replay(), replay.body(), true);
  }

  private static boolean matchesBoundary(
      GeneratedHistoryModel model, int[][] capacity, Map<Integer, String> reservations) {
    if (!java.util.Arrays.deepEquals(model.capacity, capacity)
        || !model.reservations.keySet().equals(reservations.keySet())) {
      return false;
    }
    return model.reservations.entrySet().stream()
        .allMatch(entry -> entry.getValue().status().equals(reservations.get(entry.getKey())));
  }

  private void cleanup(UUID[] accounts, String prefix) throws Exception {
    // Privileged cleanup is confined to these generated fixtures; app_role remains append-only.
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      statement.execute("SET LOCAL statement_timeout = '20s'");
      String ids = "('" + accounts[0] + "', '" + accounts[1] + "')";
      statement.executeUpdate(
          "DELETE FROM outbox WHERE payload ->> 'accountId' IN "
              + ids
              + " OR payload ->> 'fromAccountId' IN "
              + ids);
      statement.executeUpdate("DELETE FROM audit_entry WHERE account_id IN " + ids);
      statement.executeUpdate(
          "DELETE FROM operation WHERE idempotency_key LIKE 'internal-expire:%'"
              + " AND split_part(idempotency_key, ':', 2) IN (SELECT id::text FROM reservation"
              + " WHERE account_id IN "
              + ids
              + " UNION SELECT '"
              + MISSING_RESERVATION
              + "')");
      statement.executeUpdate("DELETE FROM reservation WHERE account_id IN " + ids);
      statement.executeUpdate(
          "DELETE FROM operation WHERE idempotency_key LIKE '"
              + prefix
              + "%'"
              + " OR response_body ->> 'accountId' IN "
              + ids);
      statement.executeUpdate("DELETE FROM capacity WHERE account_id IN " + ids);
      statement.executeUpdate("DELETE FROM account WHERE id IN " + ids);
      connection.commit();
    }
  }

  private void preserveFailure(
      Path directory, long seed, GeneratedHistory history, List<String> trace, Throwable failure)
      throws IOException {
    String base = "failure-" + seed;
    Files.writeString(directory.resolve(base + ".history"), history.encode());
    Files.writeString(
        directory.resolve(base + ".trace"), String.join("\n", trace) + "\n" + failure);
    String signature = signature(failure);
    List<String> minimizedTrace = new ArrayList<>(trace);
    var shrunk =
        history.shrink(
            candidate -> {
              List<String> candidateTrace = new ArrayList<>();
              try {
                runHistory(candidate, candidateTrace);
                return false;
              } catch (AssertionError | Exception candidateFailure) {
                if (signature(candidateFailure).equals(signature)) {
                  minimizedTrace.clear();
                  minimizedTrace.addAll(candidateTrace);
                  return true;
                }
                return false;
              }
            },
            Integer.getInteger("ledger.history.shrinkBudget", 100));
    Files.writeString(directory.resolve(base + "-minimal.history"), shrunk.history().encode());
    Files.writeString(
        directory.resolve(base + "-minimal.trace"),
        "seed="
            + seed
            + "\nattempts="
            + shrunk.attempts()
            + "\ndeletionMinimal="
            + shrunk.deletionMinimal()
            + "\nfailureSignature="
            + signature
            + "\nMinimal means no individual batch/command/due marker can be removed;"
            + " a bounded or schedule-sensitive reduction may remain nonminimal.\n"
            + String.join("\n", minimizedTrace));
  }

  private static String signature(Throwable failure) {
    String message = String.valueOf(failure.getMessage());
    return failure.getClass().getName()
        + ":"
        + message
            .lines()
            .filter(line -> !line.isBlank())
            .findFirst()
            .orElse("")
            .replaceAll("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", "UUID");
  }

  private static void appendSeed(
      Path path, long seed, GeneratedHistory history, String result, long start)
      throws IOException {
    int commands = history.batches().stream().mapToInt(batch -> batch.commands().size()).sum();
    Files.writeString(
        path,
        seed
            + ","
            + history.batches().size()
            + ","
            + commands
            + ","
            + result
            + ","
            + Duration.ofNanos(System.nanoTime() - start).toMillis()
            + "\n",
        java.nio.file.StandardOpenOption.APPEND);
  }
}
