package com.theater.ticketing.infrastructure;

import com.theater.shared.di.Container;
import com.theater.shared.di.Module;
import com.theater.shared.tx.UnitOfWork;
import com.theater.ticketing.domain.TicketRepository;

/**
 * ticketing BC の DI バインディング。
 *
 * <p>TK-01 で {@link TicketRepository} を bind。TK-02 で ListMyTickets / GetTicketDetail UseCase の bind
 * が追加される。
 */
public final class TicketingModule implements Module {

  @Override
  public void bind(Container container) {
    container.registerSingleton(
        TicketRepository.class, c -> new JdbcTicketRepository(c.resolve(UnitOfWork.class)));
    // TODO(TK-02): ListMyTicketsUseCase / GetTicketDetailUseCase
    // TODO(TK-04): MarkUsedUseCase (Sprint 2)
  }
}
