package com.theater.ticketing.infrastructure;

import com.theater.shared.di.Container;
import com.theater.shared.di.Module;
import com.theater.shared.tx.UnitOfWork;
import com.theater.ticketing.domain.TicketRepository;

/**
 * ticketing BC の DI バインディング。
 *
 * <p>TK-01 で {@link TicketRepository} を bind。TK-02 の ListMyTickets / GetTicketDetail UseCase は
 * ArchUnit 制約 (Infrastructure → Application 禁止) のため {@code App.bootstrap} 側で bind 済。
 */
public final class TicketingModule implements Module {

  @Override
  public void bind(Container container) {
    container.registerSingleton(
        TicketRepository.class, c -> new JdbcTicketRepository(c.resolve(UnitOfWork.class)));
    // TODO(TK-04): MarkUsedUseCase (Sprint 2)
  }
}
