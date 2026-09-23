package com.g1do.ledger.domain;

/**
 * Reservation states with irreversible commit, release, and expiry terminal transitions. Pure Java,
 * Spring-free.
 */
public enum ReservationStatus {
  RESERVED,
  COMMITTED,
  RELEASED,
  EXPIRED;

  /** Only an active reservation can reach a terminal state. */
  public boolean canTransitionTo(ReservationStatus next) {
    return this == RESERVED && (next == COMMITTED || next == RELEASED || next == EXPIRED);
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
