package com.g1do.ledger.domain;

/**
 * O1 state machine subset: {@code RESERVED -> COMMITTED} only. No Release/Transfer (O2). Pure Java,
 * Spring-free.
 */
public enum ReservationStatus {
  RESERVED,
  COMMITTED;

  /** Returns true only for the single legal O1 transition. */
  public boolean canTransitionTo(ReservationStatus next) {
    return this == RESERVED && next == COMMITTED;
  }

  public static ReservationStatus parse(String value) {
    for (ReservationStatus status : values()) {
      if (status.name().equals(value)) {
        return status;
      }
    }
    throw new IllegalArgumentException("Unknown reservation status: " + value);
  }
}
