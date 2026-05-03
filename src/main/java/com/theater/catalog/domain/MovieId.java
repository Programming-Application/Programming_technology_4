package com.theater.catalog.domain;

import java.util.Objects;

/** Movie aggregate identifier. */
public record MovieId(String value) {

  public MovieId {
    Objects.requireNonNull(value, "value");
    if (value.isBlank()) {
      throw new IllegalArgumentException("movie id must not be blank");
    }
  }
}
