package com.g1do.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.model.ExposedPort;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Real packaged application JVM and a disposable PG16 instance; never uses the Compose database.
 */
class ProcessCrashIT {
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16")
          .withCommand(
              "postgres",
              "-c",
              "fsync=on",
              "-c",
              "synchronous_commit=on",
              "-c",
              "full_page_writes=on");
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @BeforeAll
  static void startDatabase() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopDatabase() throws IOException {
    try {
      Path logs = Path.of("target", "process-crash");
      Files.createDirectories(logs);
      Files.writeString(logs.resolve("postgres.log"), POSTGRES.getLogs());
    } finally {
      POSTGRES.stop();
    }
  }

  @Test
  void applicationKilledDuringWriteRollsBackAndReplaySurvivesNextRestart() throws Exception {
    try (App app = new App("app-kill")) {
      UUID account = seed();
      String key = "app-kill-" + UUID.randomUUID();
      try (Connection blocker = blockOutbox()) {
        var pending =
            HTTP.sendAsync(app.reserve(account, key), HttpResponse.BodyHandlers.ofString());
        awaitOutboxWait();
        app.kill();
        blocker.rollback();
        pending.handle((response, error) -> null).get(10, TimeUnit.SECONDS);
      }
      assertEmpty(account, key);
      app.start();
      String original = reserve(app, account, key, 201);
      app.kill();
      app.start();
      assertThat(reserve(app, account, key, 200)).isEqualTo(original);
      assertOne(account, key);
    }
  }

  @Test
  void databaseCrashRollsBackInFlightWriteAndPreservesAcknowledgedCommit() throws Exception {
    try (App app = new App("postgres-kill")) {
      UUID account = seed();
      String key = "pg-kill-" + UUID.randomUUID();
      Connection blocker = blockOutbox();
      try {
        var pending =
            HTTP.sendAsync(app.reserve(account, key), HttpResponse.BodyHandlers.ofString());
        awaitOutboxWait();
        killDatabase();
        app.kill();
        pending.handle((response, error) -> null).get(10, TimeUnit.SECONDS);
      } finally {
        blocker.close();
      }
      startDatabaseAgain();
      assertEmpty(account, key);
      app.start();
      String original = reserve(app, account, key, 201);
      assertOne(account, key);
      killDatabase();
      app.kill();
      startDatabaseAgain();
      app.start();
      assertThat(reserve(app, account, key, 200)).isEqualTo(original);
      assertOne(account, key);
    }
  }

  @Test
  void clientWithoutResponseResolvesCommittedOutcomeAfterApplicationRestart() throws Exception {
    try (App app = new App("lost-response")) {
      UUID account = seed();
      String key = "lost-response-" + UUID.randomUUID();
      String body = body(account, key);
      try (Socket socket = new Socket("127.0.0.1", app.port)) {
        String request =
            "POST /v1/reserve HTTP/1.1\r\nHost: localhost\r\n"
                + "Content-Type: application/json\r\nIdempotency-Key: "
                + key
                + "\r\n"
                + "Content-Length: "
                + body.getBytes(StandardCharsets.UTF_8).length
                + "\r\nConnection: close\r\n\r\n"
                + body;
        socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
        // The client never consumes the HTTP response. Resolve the outcome only from durable state.
        await(
            () ->
                count(
                        "SELECT COUNT(*) FROM operation WHERE idempotency_key = ?"
                            + " AND status = 'COMPLETED'",
                        key)
                    == 1,
            "operation commits without a consumed response");
        app.kill();
      }
      String stored =
          jdbc()
              .queryForObject(
                  "SELECT response_body::text FROM operation WHERE idempotency_key = ?",
                  String.class,
                  key);
      app.start();
      assertThat(reserve(app, account, key, 200)).isEqualTo(stored);
      assertOne(account, key);
    }
  }

  private static Connection blockOutbox() throws Exception {
    Connection connection =
        DriverManager.getConnection(jdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    connection.setAutoCommit(false);
    try (var statement = connection.createStatement()) {
      statement.execute("LOCK TABLE outbox IN ACCESS EXCLUSIVE MODE");
    }
    return connection;
  }

  private static void awaitOutboxWait() throws Exception {
    await(
        () ->
            count(
                    "SELECT COUNT(*) FROM pg_stat_activity WHERE usename = 'app_role'"
                        + " AND wait_event_type = 'Lock' AND query LIKE 'INSERT INTO outbox%'")
                == 1,
        "application reaches outbox insert after business and audit writes");
  }

  private static void killDatabase() {
    POSTGRES
        .getDockerClient()
        .killContainerCmd(POSTGRES.getContainerId())
        .withSignal("KILL")
        .exec();
  }

  private static void startDatabaseAgain() throws Exception {
    POSTGRES.getDockerClient().startContainerCmd(POSTGRES.getContainerId()).exec();
    await(
        () -> {
          try {
            return jdbc().queryForObject("SELECT 1", Integer.class) == 1;
          } catch (RuntimeException unavailable) {
            return false;
          }
        },
        "PostgreSQL completes WAL recovery");
  }

  private static String jdbcUrl() {
    // Docker may allocate a new ephemeral host port on restart; inspect rather than use cached
    // metadata.
    String port =
        POSTGRES
            .getDockerClient()
            .inspectContainerCmd(POSTGRES.getContainerId())
            .exec()
            .getNetworkSettings()
            .getPorts()
            .getBindings()
            .get(new ExposedPort(5432))[0]
            .getHostPortSpec();
    return "jdbc:postgresql://"
        + POSTGRES.getHost()
        + ":"
        + port
        + "/"
        + POSTGRES.getDatabaseName();
  }

  private static JdbcTemplate jdbc() {
    return new JdbcTemplate(
        new DriverManagerDataSource(jdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
  }

  private static UUID seed() {
    UUID account = UUID.randomUUID();
    jdbc().update("INSERT INTO account (id, display_name) VALUES (?, 'process-crash')", account);
    jdbc().update("INSERT INTO capacity (account_id, total) VALUES (?, 10)", account);
    assertThat(jdbc().queryForObject("SHOW fsync", String.class)).isEqualTo("on");
    assertThat(jdbc().queryForObject("SHOW synchronous_commit", String.class)).isEqualTo("on");
    assertThat(jdbc().queryForObject("SHOW full_page_writes", String.class)).isEqualTo("on");
    return account;
  }

  private static int count(String sql, Object... parameters) {
    return jdbc().queryForObject(sql, Integer.class, parameters);
  }

  private static void assertEmpty(UUID account, String key) {
    assertThat(count("SELECT COUNT(*) FROM operation WHERE idempotency_key = ?", key)).isZero();
    assertThat(count("SELECT COUNT(*) FROM reservation WHERE account_id = ?", account)).isZero();
    assertThat(count("SELECT COUNT(*) FROM audit_entry WHERE account_id = ?", account)).isZero();
    assertThat(
            count(
                "SELECT COUNT(*) FROM outbox WHERE payload ->> 'accountId' = ?",
                account.toString()))
        .isZero();
    new InvariantChecker(jdbc(), account).assertExpectedCapacity(account, 10, 0, 0);
  }

  private static void assertOne(UUID account, String key) {
    assertThat(
            count(
                "SELECT COUNT(*) FROM operation WHERE idempotency_key = ?"
                    + " AND status = 'COMPLETED'",
                key))
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM reservation WHERE account_id = ?", account))
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM audit_entry WHERE account_id = ?", account))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT COUNT(*) FROM outbox WHERE payload ->> 'accountId' = ?",
                account.toString()))
        .isEqualTo(1);
    InvariantChecker checker = new InvariantChecker(jdbc(), account);
    checker.assertExpectedCapacity(account, 10, 1, 0);
    checker.assertO1Invariants();
  }

  private static String reserve(App app, UUID account, String key, int expected) throws Exception {
    var response = HTTP.send(app.reserve(account, key), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
    return response.body();
  }

  private static String body(UUID account, String key) {
    return "{\"accountId\":\"" + account + "\",\"amount\":1,\"idempotencyKey\":\"" + key + "\"}";
  }

  private static void await(BooleanSupplier condition, String description) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("Timed out waiting for " + description);
  }

  private static final class App implements AutoCloseable {
    private final String name;
    private Process process;
    private int port;
    private int starts;

    private App(String name) throws Exception {
      this.name = name;
      start();
    }

    private void start() throws Exception {
      try (ServerSocket socket = new ServerSocket(0)) {
        port = socket.getLocalPort();
      }
      Path logs = Path.of("target", "process-crash");
      Files.createDirectories(logs);
      Path log = logs.resolve(name + "-" + (++starts) + ".log");
      process =
          new ProcessBuilder(
                  Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                  "-jar",
                  System.getProperty("ledger.test.jar"),
                  "--server.address=127.0.0.1",
                  "--server.port=" + port,
                  "--spring.datasource.url=" + jdbcUrl(),
                  "--spring.datasource.username=app_role",
                  "--spring.datasource.password=app",
                  "--spring.flyway.user=" + POSTGRES.getUsername(),
                  "--spring.flyway.password=" + POSTGRES.getPassword(),
                  "--logging.level.root=WARN",
                  "--debug=false")
              .redirectErrorStream(true)
              .redirectOutput(log.toFile())
              .start();
      try {
        await(
            () -> {
              if (!process.isAlive()) {
                throw new AssertionError("Application exited; inspect " + log);
              }
              try {
                return HTTP.send(
                            HttpRequest.newBuilder(
                                    URI.create("http://127.0.0.1:" + port + "/health"))
                                .timeout(Duration.ofSeconds(1))
                                .build(),
                            HttpResponse.BodyHandlers.discarding())
                        .statusCode()
                    == 200;
              } catch (IOException unavailable) {
                return false;
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
              }
            },
            "packaged application HTTP startup; log=" + log);
      } catch (Exception | AssertionError failure) {
        kill();
        throw failure;
      }
    }

    private HttpRequest reserve(UUID account, String key) {
      return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/reserve"))
          .timeout(Duration.ofSeconds(15))
          .header("Content-Type", "application/json")
          .header("Idempotency-Key", key)
          .POST(HttpRequest.BodyPublishers.ofString(body(account, key)))
          .build();
    }

    private void kill() throws Exception {
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
      }
    }

    @Override
    public void close() throws Exception {
      kill();
    }
  }
}
