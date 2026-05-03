package com.theater.catalog.domain;

import java.util.Objects;

/** Screen aggregate identifier. */
public record ScreenId(String value) {

  public ScreenId {
    Objects.requireNonNull(value, "value");
    if (value.isBlank()) {
      throw new IllegalArgumentException("screen id must not be blank");
    }
  }
}
