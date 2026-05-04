package com.theater.catalog.domain;

import com.theater.shared.kernel.MovieId;
import java.time.Instant;
import java.util.Objects;

/** Movie master aggregate. */
public record Movie(
    MovieId id,
    String title,
    String description,
    int durationMinutes,
    boolean published,
    Instant createdAt,
    Instant updatedAt,
    long version) {

  public Movie {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(description, "description");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (title.isBlank()) {
      throw new IllegalArgumentException("movie title must not be blank");
    }
    if (durationMinutes <= 0) {
      throw new IllegalArgumentException("duration minutes must be positive");
    }
    if (version < 0) {
      throw new IllegalArgumentException("version must not be negative");
    }
  }
}
