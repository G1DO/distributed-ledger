# DEC-LEDGER-02 — Spring Boot 4.1.x Upgrade

Status: accepted. Date: 2026-09-10.

## Context

The service was upgraded from Spring Boot 3.5.16 to the Spring Boot 4.1 line while retaining its
Java 25 baseline. This ADR records the source-level changes required by that upgrade.

## Decision

Bump `spring-boot-starter-parent` 3.5.16 → **4.1.1**. The JDK baseline remains 25
(`maven.compiler.release=25` and enforcer `[25,27)`); the JDK 26 CI lane remains
allowed-fail.

## Breaking changes encountered (from the 4.0 migration guide)

- `spring-boot-starter-web` is deprecated → replaced with
  `spring-boot-starter-webmvc` (same behavior, Tomcat 11 / Servlet 6.1).
- Raw `flyway-core` dep was replaced with `spring-boot-starter-flyway`.
  `flyway-database-postgresql` remains alongside the starter for PostgreSQL support.
- `spring-boot-starter-test` no longer carries web test slices →
  replaced with `spring-boot-starter-webmvc-test` (brings
  `spring-boot-starter-test` transitively per the guide).
- `AutoConfigureMockMvc` moved to
  `org.springframework.boot.webmvc.test.autoconfigure` (one import
  change in `HealthControllerTest`; no behavior change).
- The service has no application-specific Jackson migration; its JSON assertions continue to use
  MockMvc `jsonPath`.

## Consequences

- `mvn verify -Pstrict` is the JDK 25 verification gate, including unit and PostgreSQL
  integration tests, checkstyle, formatting, and enforcer checks.
- The service uses the Spring Boot-managed embedded Tomcat stack.
