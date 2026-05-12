package com.theater.ordering.application;

import com.theater.reservation.domain.SeatState;
import com.theater.shared.kernel.Money;
import com.theater.shared.kernel.ReservationId;
import com.theater.shared.kernel.ScreeningId;
import java.util.List;
import java.util.Objects;

/** Checkout 画面表示用の予約サマリ。 */
public record CheckoutSummary(
    ReservationId reservationId, ScreeningId screeningId, List<SeatState> seats, Money total) {

  public CheckoutSummary {
    Objects.requireNonNull(reservationId, "reservationId");
    Objects.requireNonNull(screeningId, "screeningId");
    seats = List.copyOf(Objects.requireNonNull(seats, "seats"));
    Objects.requireNonNull(total, "total");
  }
}
