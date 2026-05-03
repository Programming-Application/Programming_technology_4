package com.theater.catalog.domain;

import java.util.Objects;

/** Seat identifier inside a screen, for example A-10. */
public record SeatId(String value) {

  public SeatId {
    Objects.requireNonNull(value, "value");
    if (value.isBlank()) {
      throw new IllegalArgumentException("seat id must not be blank");
    }
  }
}
