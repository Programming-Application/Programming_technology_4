package com.theater.catalog.application;

import com.theater.catalog.domain.CatalogQueryRepository;
import com.theater.shared.tx.TransactionalUseCase;
import com.theater.shared.tx.Tx;
import com.theater.shared.tx.UnitOfWork;
import java.util.List;
import java.util.Objects;

/** CT-01: list published movies. */
public final class ListPublishedMoviesUseCase
    extends TransactionalUseCase<ListPublishedMoviesUseCase.Command, List<MovieSummary>> {

  private final CatalogQueryRepository repository;

  public ListPublishedMoviesUseCase(UnitOfWork uow, CatalogQueryRepository repository) {
    super(uow);
    this.repository = Objects.requireNonNull(repository, "repository");
  }

  @Override
  protected Tx txMode() {
    return Tx.READ_ONLY;
  }

  @Override
  protected List<MovieSummary> handle(Command command) {
    return repository.findPublishedMovies().stream().map(MovieSummary::from).toList();
  }

  public record Command() {}
}
