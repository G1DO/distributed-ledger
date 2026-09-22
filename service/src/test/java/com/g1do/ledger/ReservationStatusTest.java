package com.g1do.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.g1do.ledger.domain.ReservationStatus;
import org.junit.jupiter.api.Test;

class ReservationStatusTest {

  @Test
  void onlyReservedCanTransitionAndTerminalStatesCannotBeReopened() {
    for (ReservationStatus current : ReservationStatus.values()) {
      assertThat(ReservationStatus.parse(current.name())).isEqualTo(current);
      for (ReservationStatus next : ReservationStatus.values()) {
        assertThat(current.canTransitionTo(next))
            .as("%s -> %s", current, next)
            .isEqualTo(current == ReservationStatus.RESERVED && next != ReservationStatus.RESERVED);
      }
    }
    assertThatThrownBy(() -> ReservationStatus.parse("INVALID"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
