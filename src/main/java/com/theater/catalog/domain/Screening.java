package com.theater.catalog.domain;

import java.time.Instant;
import java.util.Objects;

/** Scheduled movie screening. */
public record Screening(
    ScreeningId id,
    MovieId movieId,
    ScreenId screenId,
    Instant startTime,
    Instant endTime,
    Instant salesStartAt,
    Instant salesEndAt,
    ScreeningStatus status,
    boolean privateScreening,
    int availableSeatCount,
    int reservedSeatCount,
    int soldSeatCount,
    Instant lastUpdated,
    Instant createdAt,
    Instant updatedAt,
    long version) {

  public Screening {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(movieId, "movieId");
    Objects.requireNonNull(screenId, "screenId");
    Objects.requireNonNull(startTime, "startTime");
    Objects.requireNonNull(endTime, "endTime");
    Objects.requireNonNull(salesStartAt, "salesStartAt");
    Objects.requireNonNull(salesEndAt, "salesEndAt");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(lastUpdated, "lastUpdated");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (!startTime.isBefore(endTime)) {
      throw new IllegalArgumentException("screening start time must be before end time");
    }
    if (salesStartAt.isAfter(salesEndAt) || salesEndAt.isAfter(startTime)) {
      throw new IllegalArgumentException("sales window must end before screening starts");
    }
    if (availableSeatCount < 0 || reservedSeatCount < 0 || soldSeatCount < 0) {
      throw new IllegalArgumentException("seat counts must not be negative");
    }
    if (version < 0) {
      throw new IllegalArgumentException("version must not be negative");
    }
  }
}
