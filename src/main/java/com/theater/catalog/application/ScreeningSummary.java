package com.theater.catalog.application;

import com.theater.catalog.domain.CatalogQueryRepository.ScreeningWithMovie;
import java.time.Instant;

/** Screening list item for CT-04 and movie detail. */
public record ScreeningSummary(
    String screeningId,
    String movieTitle,
    String screenName,
    Instant startTime,
    Instant endTime,
    int availableSeatCount,
    int reservedSeatCount,
    int soldSeatCount) {

  public static ScreeningSummary from(ScreeningWithMovie row) {
    var screening = row.screening();
    return new ScreeningSummary(
        screening.id().value(),
        row.movieTitle(),
        row.screenName(),
        screening.startTime(),
        screening.endTime(),
        screening.availableSeatCount(),
        screening.reservedSeatCount(),
        screening.soldSeatCount());
  }
}
