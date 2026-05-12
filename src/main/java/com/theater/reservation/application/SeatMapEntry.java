package com.theater.reservation.application;

import com.theater.reservation.domain.SeatStateStatus;
import com.theater.shared.kernel.SeatId;
import java.util.Objects;

/** RV-01 LoadSeatMap の UI 表示向け DTO。 */
public record SeatMapEntry(SeatId seatId, SeatStateStatus status, int price) {

  public SeatMapEntry {
    Objects.requireNonNull(seatId, "seatId");
    Objects.requireNonNull(status, "status");
    if (price < 0) {
      throw new IllegalArgumentException("price must not be negative");
    }
  }
}
