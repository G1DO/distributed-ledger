package com.g1do.ledger.service;

import java.sql.SQLException;
import java.util.UUID;

/** Shared validation + SQL-state helpers for the O1 slice. */
public final class SliceSupport {

  private SliceSupport() {}

  public static void requireHeaderMatchesBody(String headerKey, String bodyKey) {
    if (headerKey == null || headerKey.isBlank()) {
      throw new BadRequestException("Missing Idempotency-Key header");
    }
    if (headerKey.length() > 64) {
      throw new BadRequestException("Idempotency-Key must be 1..64 chars");
    }
    if (!headerKey.equals(bodyKey)) {
      throw new BadRequestException("Idempotency-Key header must equal body idempotencyKey");
    }
  }

  public static UUID requireUuidV4(String value, String field) {
    try {
      UUID parsed = UUID.fromString(value);
      if (parsed.version() != 4) {
        throw new BadRequestException(field + " must be UUIDv4");
      }
      return parsed;
    } catch (IllegalArgumentException e) {
      throw new BadRequestException(field + " must be UUIDv4");
    }
  }

  public static SQLException rootSQLException(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof SQLException sql) {
        return sql;
      }
      current = current.getCause();
    }
    return null;
  }

  public static String sqlState(Throwable failure) {
    SQLException root = rootSQLException(failure);
    return root == null ? null : root.getSQLState();
  }
}
