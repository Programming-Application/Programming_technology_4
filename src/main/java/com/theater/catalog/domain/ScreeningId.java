package com.theater.catalog.domain;

import java.util.Objects;

/** Screening aggregate identifier. */
public record ScreeningId(String value) {

  public ScreeningId {
    Objects.requireNonNull(value, "value");
    if (value.isBlank()) {
      throw new IllegalArgumentException("screening id must not be blank");
    }
  }
}
