package com.theater.catalog.application;

import com.theater.catalog.domain.CatalogQueryRepository;
import com.theater.catalog.domain.ScreeningId;
import com.theater.shared.error.NotFoundException;
import com.theater.shared.tx.TransactionalUseCase;
import com.theater.shared.tx.Tx;
import com.theater.shared.tx.UnitOfWork;
import java.util.Objects;

/** CT-05: get screening detail with seat counters. */
public final class GetScreeningDetailUseCase
    extends TransactionalUseCase<GetScreeningDetailUseCase.Command, ScreeningDetailView> {

  private final CatalogQueryRepository repository;

  public GetScreeningDetailUseCase(UnitOfWork uow, CatalogQueryRepository repository) {
    super(uow);
    this.repository = Objects.requireNonNull(repository, "repository");
  }

  @Override
  protected Tx txMode() {
    return Tx.READ_ONLY;
  }

  @Override
  protected ScreeningDetailView handle(Command command) {
    var id = new ScreeningId(command.screeningId());
    return repository
        .findScreeningDetail(id)
        .map(ScreeningDetailView::from)
        .orElseThrow(() -> new NotFoundException("Screening", id.value()));
  }

  public record Command(String screeningId) {}
}
