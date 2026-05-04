package com.theater.catalog.application;

import com.theater.catalog.domain.Movie;

/** Movie list item for CT-01/02. */
public record MovieSummary(String movieId, String title, String description, int durationMinutes) {

  public static MovieSummary from(Movie movie) {
    return new MovieSummary(
        movie.id().value(), movie.title(), movie.description(), movie.durationMinutes());
  }
}
