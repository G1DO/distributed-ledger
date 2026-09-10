# DEC-LEDGER-02 — Spring Boot 4.1.x Upgrade

Status: accepted. Date: 2026-09-10.

## Context

Boot 3.5.x reached OSS EOL in June 2026. Its managed pins carry 8 known
vulnerabilities reported by osv-scanner (jackson-databind 2.21.4,
log4j-api 2.24.3, tomcat-embed-core 10.1.55, postgresql 42.7.11),
including 3 Critical CVEs in Tomcat. Staying on 3.5.x means shipping
known CVEs with no upstream fixes. Boot 4.0 OSS ends Dec 2026, so the
4.0 line would repeat the EOL problem within months.

## Decision

Bump `spring-boot-starter-parent` 3.5.16 → **4.1.1** (latest stable,
OSS support to Jun 2027). JDK baseline unchanged: 25 LTS
(`maven.compiler.release=25`, enforcer `[25,27)` still valid — Boot 4.1
supports Java 17 through 26). JDK 26 CI lane stays allowed-fail.

## Breaking changes encountered (from the 4.0 migration guide)

- `spring-boot-starter-web` is deprecated → replaced with
  `spring-boot-starter-webmvc` (same behavior, Tomcat 11 / Servlet 6.1).
- Raw `flyway-core` dep replaced with `spring-boot-starter-flyway`
  (manages Flyway 12.4.0). `flyway-database-postgresql` is still
  required alongside the starter — without it both Flyway 11 and 12
  fail at startup with `Unsupported Database: PostgreSQL 16.10`
  (proven twice at compose time, fixed the same way twice).
- `spring-boot-starter-test` no longer carries web test slices →
  replaced with `spring-boot-starter-webmvc-test` (brings
  `spring-boot-starter-test` transitively per the guide).
- `AutoConfigureMockMvc` moved to
  `org.springframework.boot.webmvc.test.autoconfigure` (one import
  change in `HealthControllerTest`; no behavior change).
- Jackson 3 (`tools.jackson.core:jackson-databind:3.1.5`) is now the
  default. No code changes required — the skeleton touches Jackson only
  via MockMvc `jsonPath`, which passes unchanged.
- `spring-boot-starter-jdbc`, `spring-boot-starter-validation`,
  Postgres driver, H2, ArchUnit 1.5.0, spotless, checkstyle, enforcer:
  no changes required. ArchUnit passes against Spring 7 bytecode.

## Pinned versions after bump (from `mvn dependency:list`)

- Jackson 3.1.5 (new `tools.jackson` coords — old CVEs not applicable)
- log4j-api 2.25.5 (= OSV-suggested fix)
- tomcat-embed-core 11.0.24 (past the 10.1.58 fix line)
- postgresql 42.7.13 (past the 42.7.12 fix line)
- Flyway 12.4.0 via the starter

OSV job stays informational (`continue-on-error`): new disclosures can
appear faster than BOM updates, and a blocking scanner would hold `main`
hostage to third-party CVE feeds.

## Consequences

- `mvn verify -Pstrict` green on JDK 25 (5 tests, checkstyle, enforcer).
- Undertow users n/a (we use Tomcat); war/launch-script removals n/a.
- Rollback = revert commit (Boot 3.5.16 pins restore byte-for-byte).
