package com.theater.catalog.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Read-side repository for catalog use cases CT-01..05. */
public interface CatalogQueryRepository {

  List<Movie> findPublishedMovies();

  List<Movie> searchMoviesByTitle(String titlePart);

  Optional<Movie> findMovieById(MovieId id);

  List<ScreeningWithMovie> findUpcomingScreenings(Instant from, Instant to);

  List<ScreeningWithMovie> findScreeningsByMovie(MovieId movieId, Instant from);

  Optional<ScreeningDetail> findScreeningDetail(ScreeningId id);

  /** Screening row joined with movie and screen display data. */
  record ScreeningWithMovie(
      Screening screening, String movieTitle, String screenName, int durationMinutes) {}

  /** Screening detail with denormalized seat counters. */
  record ScreeningDetail(Screening screening, Movie movie, Screen screen) {}
}
