package com.theater.ordering.application;

import com.theater.reservation.domain.Reservation;
import com.theater.reservation.domain.ReservationRepository;
import com.theater.reservation.domain.ReservationStatus;
import com.theater.reservation.domain.SeatState;
import com.theater.reservation.domain.SeatStateRepository;
import com.theater.shared.error.IllegalStateTransitionException;
import com.theater.shared.error.NotFoundException;
import com.theater.shared.kernel.Clock;
import com.theater.shared.kernel.Currency;
import com.theater.shared.kernel.Money;
import com.theater.shared.kernel.ReservationId;
import com.theater.shared.kernel.UserId;
import com.theater.shared.tx.TransactionalUseCase;
import com.theater.shared.tx.Tx;
import com.theater.shared.tx.UnitOfWork;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** HOLD 中 reservation の checkout 表示内容を作る query use case。 */
public final class StartCheckoutUseCase
    extends TransactionalUseCase<StartCheckoutUseCase.Command, CheckoutSummary> {

  private final ReservationRepository reservationRepo;
  private final SeatStateRepository seatStateRepo;
  private final Clock clock;

  public StartCheckoutUseCase(
      UnitOfWork uow,
      ReservationRepository reservationRepo,
      SeatStateRepository seatStateRepo,
      Clock clock) {
    super(uow);
    this.reservationRepo = Objects.requireNonNull(reservationRepo, "reservationRepo");
    this.seatStateRepo = Objects.requireNonNull(seatStateRepo, "seatStateRepo");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public record Command(ReservationId reservationId, UserId userId) {
    public Command {
      Objects.requireNonNull(reservationId, "reservationId");
      Objects.requireNonNull(userId, "userId");
    }
  }

  @Override
  protected Tx txMode() {
    return Tx.READ_ONLY;
  }

  @Override
  protected CheckoutSummary handle(Command cmd) {
    Reservation reservation =
        reservationRepo
            .findById(cmd.reservationId())
            .orElseThrow(() -> new NotFoundException("Reservation", cmd.reservationId().value()));
    requireOwnedByCurrentUser(reservation, cmd.userId());
    requireHoldAndAlive(reservation, clock.now());

    List<SeatState> seats =
        seatStateRepo.findByScreening(reservation.screeningId()).stream()
            .filter(seat -> reservation.id().equals(seat.reservationId()))
            .toList();
    Money total =
        seats.stream()
            .map(seat -> new Money(seat.price(), Currency.JPY))
            .reduce(Money.zero(Currency.JPY), Money::plus);

    return new CheckoutSummary(reservation.id(), reservation.screeningId(), seats, total);
  }

  private static void requireOwnedByCurrentUser(Reservation reservation, UserId userId) {
    if (!reservation.userId().equals(userId)) {
      throw new IllegalStateTransitionException(
          "Reservation", "OTHER_USER", "owned by current user");
    }
  }

  private static void requireHoldAndAlive(Reservation reservation, Instant now) {
    if (reservation.status() != ReservationStatus.HOLD) {
      throw new IllegalStateTransitionException(
          "Reservation", reservation.status().name(), "HOLD required");
    }
    if (reservation.expiresAt().isBefore(now)) {
      throw new IllegalStateTransitionException("Reservation", "EXPIRED", "still in HOLD");
    }
  }
}
