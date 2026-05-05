package com.theater.reservation.infrastructure;

import com.theater.shared.di.Container;
import com.theater.shared.di.Module;

/** reservation BC の DI バインディング (skeleton)。RV-01 で実装を bind、RV-02 で UseCase を bind。 */
public final class ReservationModule implements Module {

  @Override
  public void bind(Container container) {
    // TODO(RV-01): JdbcReservationRepository / JdbcSeatStateRepository を bind
    // TODO(RV-02): HoldSeatsUseCase / LoadSeatMapUseCase
    // TODO(RV-03): ReleaseHoldUseCase
    // TODO(RV-04): ExpireHoldsJob
  }
}
