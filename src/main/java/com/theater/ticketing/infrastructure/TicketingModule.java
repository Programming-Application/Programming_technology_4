package com.theater.ticketing.infrastructure;

import com.theater.shared.di.Container;
import com.theater.shared.di.Module;

/** ticketing BC の DI バインディング (skeleton)。TK-01 で TicketRepository を bind、TK-02 で UseCase を bind。 */
public final class TicketingModule implements Module {

  @Override
  public void bind(Container container) {
    // TODO(TK-01): JdbcTicketRepository を bind
    // TODO(TK-02): ListMyTicketsUseCase / GetTicketDetailUseCase
    // TODO(TK-04): MarkUsedUseCase  (Sprint 2)
  }
}
