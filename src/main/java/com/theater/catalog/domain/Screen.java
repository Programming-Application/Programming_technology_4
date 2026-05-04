package com.theater.catalog.domain;

import com.theater.shared.kernel.ScreenId;
import java.time.Instant;
import java.util.Objects;

/** Physical theater screen. */
public record Screen(
    ScreenId id, String name, int totalSeats, Instant createdAt, Instant updatedAt) {

  public Screen {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (name.isBlank()) {
      throw new IllegalArgumentException("screen name must not be blank");
    }
    if (totalSeats <= 0) {
      throw new IllegalArgumentException("total seats must be positive");
    }
  }
}
