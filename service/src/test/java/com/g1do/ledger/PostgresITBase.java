package com.g1do.ledger;

import com.g1do.ledger.app.LedgerApplication;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
    classes = LedgerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestPropertySource(properties = "ledger.expiry.enabled=false")
public abstract class PostgresITBase {

  /**
   * Singleton container shared by all IT classes in this JVM. Per-class containers break Spring's
   * context cache (second class would reuse a context pointing at the first class's stopped
   * container), so the container is started once in a static initializer instead of via
   * {@code @Testcontainers}/{@code @Container}.
   */
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "app_role");
    registry.add("spring.datasource.password", () -> "app");
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.flyway.enabled", () -> "true");
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
  }

  @Autowired protected JdbcTemplate jdbc;

  @Autowired protected MockMvc mockMvc;

  /**
   * Explicit app_role connection. The Spring datasource already runs as app_role, but this
   * dedicated connection keeps the REVOKE test immune to future wiring changes: an owner or
   * superuser connection would bypass REVOKE and give a false green.
   */
  protected Connection appConnection() throws SQLException {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app_role", "app");
  }

  protected static SQLException rootSQLException(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof SQLException sql) {
        return sql;
      }
      current = current.getCause();
    }
    return null;
  }
}
