package com.theater.catalog.application;

import com.theater.catalog.domain.CatalogQueryRepository;
import com.theater.shared.error.NotFoundException;
import com.theater.shared.kernel.Clock;
import com.theater.shared.kernel.MovieId;
import com.theater.shared.tx.TransactionalUseCase;
import com.theater.shared.tx.Tx;
import com.theater.shared.tx.UnitOfWork;
import java.util.Objects;

/** CT-03: get movie detail with upcoming screenings. */
public final class GetMovieDetailUseCase
    extends TransactionalUseCase<GetMovieDetailUseCase.Command, MovieDetailView> {

  private final CatalogQueryRepository repository;
  private final Clock clock;

  public GetMovieDetailUseCase(UnitOfWork uow, CatalogQueryRepository repository, Clock clock) {
    super(uow);
    this.repository = Objects.requireNonNull(repository, "repository");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  protected Tx txMode() {
    return Tx.READ_ONLY;
  }

  @Override
  protected MovieDetailView handle(Command command) {
    var id = new MovieId(command.movieId());
    var movie =
        repository.findMovieById(id).orElseThrow(() -> new NotFoundException("Movie", id.value()));
    var screenings =
        repository.findScreeningsByMovie(id, clock.now()).stream()
            .map(ScreeningSummary::from)
            .toList();
    return new MovieDetailView(
        movie.id().value(),
        movie.title(),
        movie.description(),
        movie.durationMinutes(),
        screenings);
  }

  public record Command(String movieId) {}
}
