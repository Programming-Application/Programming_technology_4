package com.theater.catalog.application;

import com.theater.catalog.domain.CatalogQueryRepository.ScreeningDetail;
import java.time.Instant;

/** Screening detail for CT-05. */
public record ScreeningDetailView(
    String screeningId,
    String movieId,
    String movieTitle,
    String movieDescription,
    String screenId,
    String screenName,
    Instant startTime,
    Instant endTime,
    int availableSeatCount,
    int reservedSeatCount,
    int soldSeatCount) {

  public static ScreeningDetailView from(ScreeningDetail detail) {
    var screening = detail.screening();
    var movie = detail.movie();
    var screen = detail.screen();
    return new ScreeningDetailView(
        screening.id().value(),
        movie.id().value(),
        movie.title(),
        movie.description(),
        screen.id().value(),
        screen.name(),
        screening.startTime(),
        screening.endTime(),
        screening.availableSeatCount(),
        screening.reservedSeatCount(),
        screening.soldSeatCount());
  }
}
