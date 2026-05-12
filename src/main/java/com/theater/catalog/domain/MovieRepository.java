package com.theater.catalog.domain;

/** Write-side repository for movie admin operations. */
public interface MovieRepository {

  void save(Movie movie);
}
