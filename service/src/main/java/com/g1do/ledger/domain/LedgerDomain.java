package com.g1do.ledger.domain;

/** Pure-domain marker. Must not depend on Spring or JPA (guarded by ArchUnit). */
public final class LedgerDomain {
  private LedgerDomain() {}

  public static String name() {
    return "ledger";
  }
}
