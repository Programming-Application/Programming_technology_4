package com.theater.catalog.application;

import com.theater.catalog.domain.CatalogQueryRepository;
import com.theater.shared.kernel.Clock;
import com.theater.shared.tx.TransactionalUseCase;
import com.theater.shared.tx.Tx;
import com.theater.shared.tx.UnitOfWork;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** CT-04: list upcoming screenings for the next week. */
public final class ListUpcomingScreeningsUseCase
    extends TransactionalUseCase<ListUpcomingScreeningsUseCase.Command, List<ScreeningSummary>> {

  private static final Duration LOOKAHEAD = Duration.ofDays(7);

  private final CatalogQueryRepository repository;
  private final Clock clock;

  public ListUpcomingScreeningsUseCase(
      UnitOfWork uow, CatalogQueryRepository repository, Clock clock) {
    super(uow);
    this.repository = Objects.requireNonNull(repository, "repository");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  protected Tx txMode() {
    return Tx.READ_ONLY;
  }

  @Override
  protected List<ScreeningSummary> handle(Command command) {
    var now = clock.now();
    return repository.findUpcomingScreenings(now, now.plus(LOOKAHEAD)).stream()
        .map(ScreeningSummary::from)
        .toList();
  }

  public record Command() {}
}
