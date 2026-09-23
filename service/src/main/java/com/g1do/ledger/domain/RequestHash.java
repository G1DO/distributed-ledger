package com.g1do.ledger.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Pure-domain idempotency hashing.
 *
 * <p>Must stay Spring-free (guarded by ArchUnit). Canonical JSON uses sorted keys and minimal
 * escaping so {@code request_hash = sha256(canonical body)} is stable across retries.
 */
public final class RequestHash {

  private RequestHash() {}

  /** Canonical form for reserve: keys sorted alphabetically. */
  public static String canonicalReserve(
      String accountId, int amount, String idempotencyKey, Integer ttlSec) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\"accountId\":\"").append(escape(accountId)).append("\"");
    sb.append(",\"amount\":").append(amount);
    sb.append(",\"idempotencyKey\":\"").append(escape(idempotencyKey)).append("\"");
    if (ttlSec != null) {
      sb.append(",\"ttlSec\":").append(ttlSec);
    }
    sb.append("}");
    return sb.toString();
  }

  /** Canonical form for commit: keys sorted alphabetically. */
  public static String canonicalCommit(String reservationId, String idempotencyKey) {
    return "{\"idempotencyKey\":\""
        + escape(idempotencyKey)
        + "\",\"reservationId\":\""
        + escape(reservationId)
        + "\"}";
  }

  /** Release has the same canonical body as commit; the operation claim also checks its type. */
  public static String canonicalRelease(String reservationId, String idempotencyKey) {
    return canonicalCommit(reservationId, idempotencyKey);
  }

  /** Canonical form for transfer: direction matters and keys are sorted alphabetically. */
  public static String canonicalTransfer(
      String fromAccountId, String toAccountId, int amount, String idempotencyKey) {
    return "{\"amount\":"
        + amount
        + ",\"fromAccountId\":\""
        + escape(fromAccountId)
        + "\",\"idempotencyKey\":\""
        + escape(idempotencyKey)
        + "\",\"toAccountId\":\""
        + escape(toAccountId)
        + "\"}";
  }

  public static String sha256Hex(String canonical) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  public static String escape(String value) {
    StringBuilder out = new StringBuilder(value.length() + 8);
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"':
          out.append("\\\"");
          break;
        case '\\':
          out.append("\\\\");
          break;
        case '\n':
          out.append("\\n");
          break;
        case '\r':
          out.append("\\r");
          break;
        case '\t':
          out.append("\\t");
          break;
        default:
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
          break;
      }
    }
    return out.toString();
  }
}
