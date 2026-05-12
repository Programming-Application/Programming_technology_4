package com.theater.catalog.application;

import com.theater.catalog.domain.CatalogQueryRepository;
import com.theater.catalog.domain.Movie;
import com.theater.catalog.domain.MovieRepository;
import com.theater.shared.error.NotFoundException;
import com.theater.shared.kernel.Clock;
import com.theater.shared.kernel.MovieId;
import com.theater.shared.tx.TransactionalUseCase;
import com.theater.shared.tx.UnitOfWork;
import java.util.Objects;

/** CT-08: publish a movie from admin operations. */
public final class PublishMovieUseCase
    extends TransactionalUseCase<PublishMovieUseCase.Command, Void> {

  private final CatalogQueryRepository queryRepository;
  private final MovieRepository movieRepository;
  private final Clock clock;

  public PublishMovieUseCase(
      UnitOfWork uow,
      CatalogQueryRepository queryRepository,
      MovieRepository movieRepository,
      Clock clock) {
    super(uow);
    this.queryRepository = Objects.requireNonNull(queryRepository, "queryRepository");
    this.movieRepository = Objects.requireNonNull(movieRepository, "movieRepository");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  protected Void handle(Command command) {
    var id = new MovieId(command.movieId());
    Movie movie =
        queryRepository
            .findMovieById(id)
            .orElseThrow(() -> new NotFoundException("Movie", id.value()));
    if (movie.published()) {
      return null;
    }

    movieRepository.save(
        new Movie(
            movie.id(),
            movie.title(),
            movie.description(),
            movie.durationMinutes(),
            true,
            movie.createdAt(),
            clock.now(),
            movie.version()));
    return null;
  }

  public record Command(String movieId) {}
}
