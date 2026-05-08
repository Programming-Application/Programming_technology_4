package com.theater.reservation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.shared.kernel.ReservationId;
import com.theater.shared.kernel.ScreeningId;
import com.theater.shared.kernel.UserId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReservationTest {

  private static final Instant NOW = Instant.parse("2026-05-04T00:00:00Z");

  @Test
  void hold_reservation_constructs_with_expires_at() {
    var reservation = hold();
    assertThat(reservation.status()).isEqualTo(ReservationStatus.HOLD);
  }

  @Test
  void hold_status_requires_expires_at() {
    assertThatThrownBy(
            () ->
                new Reservation(
                    new ReservationId("r-1"),
                    new UserId("u-1"),
                    new ScreeningId("s-1"),
                    ReservationStatus.HOLD,
                    null,
                    NOW,
                    NOW,
                    0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Reservation hold() {
    return new Reservation(
        new ReservationId("r-1"),
        new UserId("u-1"),
        new ScreeningId("s-1"),
        ReservationStatus.HOLD,
        NOW.plusSeconds(600),
        NOW,
        NOW,
        0);
  }
}
