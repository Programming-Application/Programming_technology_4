package com.theater.reservation.application;

import com.theater.reservation.domain.ReservationRepository;
import com.theater.reservation.domain.ScreeningCounterRepository;
import com.theater.reservation.domain.SeatStateRepository;
import com.theater.shared.kernel.Clock;
import com.theater.shared.tx.Tx;
import com.theater.shared.tx.UnitOfWork;
import java.time.Instant;
import java.util.Objects;

/** Expires timed-out HOLD reservations and releases their seats back to AVAILABLE. */
public final class ExpireHoldsJob implements Runnable {

  static final int BATCH_SIZE = 100;

  private final UnitOfWork uow;
  private final ReservationRepository reservationRepo;
  private final SeatStateRepository seatStateRepo;
  private final ScreeningCounterRepository counterRepo;
  private final Clock clock;

  public ExpireHoldsJob(
      UnitOfWork uow,
      ReservationRepository reservationRepo,
      SeatStateRepository seatStateRepo,
      ScreeningCounterRepository counterRepo,
      Clock clock) {
    this.uow = Objects.requireNonNull(uow, "uow");
    this.reservationRepo = Objects.requireNonNull(reservationRepo, "reservationRepo");
    this.seatStateRepo = Objects.requireNonNull(seatStateRepo, "seatStateRepo");
    this.counterRepo = Objects.requireNonNull(counterRepo, "counterRepo");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public void run() {
    int expired;
    do {
      expired = expireBatch(clock.now());
    } while (expired == BATCH_SIZE);
  }

  int expireBatch(Instant now) {
    Objects.requireNonNull(now, "now");
    return uow.execute(
        Tx.REQUIRED,
        () -> {
          var expiring = reservationRepo.findExpiring(now, BATCH_SIZE);
          for (var reservationId : expiring) {
            var reservation = reservationRepo.findById(reservationId).orElseThrow();
            int released = seatStateRepo.releaseByReservation(reservationId, now);
            reservationRepo.save(reservation.toExpired(now));
            counterRepo.adjust(reservation.screeningId(), released, -released, 0, now);
          }
          return expiring.size();
        });
  }
}
