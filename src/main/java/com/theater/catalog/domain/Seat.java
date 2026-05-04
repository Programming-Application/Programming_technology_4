package com.theater.catalog.domain;

import com.theater.shared.kernel.ScreenId;
import com.theater.shared.kernel.SeatId;
import java.util.Objects;

/** Seat master data belonging to a screen. */
public record Seat(
    ScreenId screenId, SeatId id, String row, int number, SeatType type, boolean active) {

  public Seat {
    Objects.requireNonNull(screenId, "screenId");
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(row, "row");
    Objects.requireNonNull(type, "type");
    if (row.isBlank()) {
      throw new IllegalArgumentException("seat row must not be blank");
    }
    if (number <= 0) {
      throw new IllegalArgumentException("seat number must be positive");
    }
  }
}
