package com.theater.reservation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.shared.kernel.ReservationId;
import com.theater.shared.kernel.ScreeningId;
import com.theater.shared.kernel.SeatId;
import com.theater.shared.kernel.TicketId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SeatStateTest {

  private static final Instant NOW = Instant.parse("2026-05-04T00:00:00Z");

  @Test
  void available_seat_constructs_without_related_ids() {
    var seat = available();
    assertThat(seat.status()).isEqualTo(SeatStateStatus.AVAILABLE);
  }

  @Test
  void hold_status_requires_reservation_and_expiry_without_ticket() {
    assertThatThrownBy(
            () ->
                new SeatState(
                    new ScreeningId("s-1"),
                    new SeatId("A-1"),
                    SeatStateStatus.HOLD,
                    new ReservationId("r-1"),
                    NOW.plusSeconds(600),
                    new TicketId("t-1"),
                    1500,
                    0,
                    NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void sold_status_requires_ticket() {
    assertThatThrownBy(
            () ->
                new SeatState(
                    new ScreeningId("s-1"),
                    new SeatId("A-1"),
                    SeatStateStatus.SOLD,
                    new ReservationId("r-1"),
                    null,
                    null,
                    1500,
                    0,
                    NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static SeatState available() {
    return new SeatState(
        new ScreeningId("s-1"),
        new SeatId("A-1"),
        SeatStateStatus.AVAILABLE,
        null,
        null,
        null,
        1500,
        0,
        NOW);
  }
}
