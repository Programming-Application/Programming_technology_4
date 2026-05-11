package com.theater.reservation.infrastructure;

import com.theater.reservation.domain.ReservationRepository;
import com.theater.reservation.domain.ScreeningCounterRepository;
import com.theater.reservation.domain.SeatStateRepository;
import com.theater.shared.di.Container;
import com.theater.shared.di.Module;
import com.theater.shared.tx.UnitOfWork;

/** reservation BC の DI バインディング。 */
public final class ReservationModule implements Module {

  @Override
  public void bind(Container container) {
    container.registerSingleton(
        ReservationRepository.class,
        c -> new JdbcReservationRepository(c.resolve(UnitOfWork.class)));
    container.registerSingleton(
        SeatStateRepository.class, c -> new JdbcSeatStateRepository(c.resolve(UnitOfWork.class)));
    container.registerSingleton(
        ScreeningCounterRepository.class,
        c -> new JdbcScreeningCounterRepository(c.resolve(UnitOfWork.class)));
    // TODO(RV-04): ExpireHoldsJob
  }
}
