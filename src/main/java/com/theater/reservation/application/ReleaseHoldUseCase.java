package com.theater.reservation.application;

import com.theater.reservation.domain.ReservationRepository;
import com.theater.reservation.domain.ReservationStatus;
import com.theater.reservation.domain.ScreeningCounterRepository;
import com.theater.reservation.domain.SeatStateRepository;
import com.theater.shared.error.IllegalStateTransitionException;
import com.theater.shared.error.NotFoundException;
import com.theater.shared.kernel.Clock;
import com.theater.shared.kernel.ReservationId;
import com.theater.shared.kernel.UserId;
import com.theater.shared.tx.TransactionalUseCase;
import com.theater.shared.tx.UnitOfWork;
import java.time.Instant;
import java.util.Objects;

/** HOLD 中の予約をユーザ自身が取り消し、座席を AVAILABLE に戻す。 */
public final class ReleaseHoldUseCase
    extends TransactionalUseCase<ReleaseHoldUseCase.Command, Void> {

  private final ReservationRepository reservationRepo;
  private final SeatStateRepository seatStateRepo;
  private final ScreeningCounterRepository screeningCounterRepo;
  private final Clock clock;

  public ReleaseHoldUseCase(
      UnitOfWork uow,
      ReservationRepository reservationRepo,
      SeatStateRepository seatStateRepo,
      ScreeningCounterRepository screeningCounterRepo,
      Clock clock) {
    super(uow);
    this.reservationRepo = Objects.requireNonNull(reservationRepo, "reservationRepo");
    this.seatStateRepo = Objects.requireNonNull(seatStateRepo, "seatStateRepo");
    this.screeningCounterRepo =
        Objects.requireNonNull(screeningCounterRepo, "screeningCounterRepo");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public record Command(UserId userId, ReservationId reservationId) {
    public Command {
      Objects.requireNonNull(userId, "userId");
      Objects.requireNonNull(reservationId, "reservationId");
    }
  }

  @Override
  protected Void handle(Command cmd) {
    var reservation =
        reservationRepo
            .findById(cmd.reservationId())
            .orElseThrow(() -> new NotFoundException("Reservation", cmd.reservationId().value()));

    if (!reservation.userId().equals(cmd.userId())) {
      throw new SecurityException(
          "Reservation owner mismatch: owner="
              + reservation.userId().value()
              + ", requester="
              + cmd.userId().value());
    }
    if (reservation.status() != ReservationStatus.HOLD) {
      throw new IllegalStateTransitionException(
          "Reservation", reservation.status().name(), ReservationStatus.CANCELED.name());
    }

    Instant now = clock.now();
    int released = seatStateRepo.releaseByReservation(cmd.reservationId(), now);
    reservationRepo.save(reservation.toCanceled(now));
    screeningCounterRepo.adjust(reservation.screeningId(), released, -released, 0, now);
    return null;
  }
}
