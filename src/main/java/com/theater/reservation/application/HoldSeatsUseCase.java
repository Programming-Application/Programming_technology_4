package com.theater.reservation.application;

import com.theater.reservation.domain.Reservation;
import com.theater.reservation.domain.ReservationRepository;
import com.theater.reservation.domain.ReservationStatus;
import com.theater.reservation.domain.ScreeningCounterRepository;
import com.theater.reservation.domain.SeatStateRepository;
import com.theater.shared.error.ConflictException;
import com.theater.shared.kernel.Clock;
import com.theater.shared.kernel.IdGenerator;
import com.theater.shared.kernel.ReservationId;
import com.theater.shared.kernel.ScreeningId;
import com.theater.shared.kernel.SeatId;
import com.theater.shared.kernel.UserId;
import com.theater.shared.tx.TransactionalUseCase;
import com.theater.shared.tx.UnitOfWork;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * RV-02 選んだ座席を HOLD する。ダブルブッキング防止の中核 UseCase。
 *
 * <p>Tx 内の書込順序:
 *
 * <ol>
 *   <li>Reservation INSERT (seat_states FK を先に満たす + Tx の最初の書込でライトロック取得)
 *   <li>seat_states UPDATE WHERE status='AVAILABLE' (影響行数で衝突検出)
 *   <li>screenings カウンタ UPDATE
 * </ol>
 *
 * <p>seat_states.reservation_id → reservations の FK が IMMEDIATE のため、Reservation を先に保存する。
 */
public final class HoldSeatsUseCase
    extends TransactionalUseCase<HoldSeatsUseCase.Command, HoldSeatsUseCase.Result> {

  /** HOLD 期限のデフォルト (10分)。 */
  public static final Duration DEFAULT_HOLD_DURATION = Duration.ofMinutes(10);

  private final SeatStateRepository seatStateRepo;
  private final ReservationRepository reservationRepo;
  private final ScreeningCounterRepository screeningCounterRepo;
  private final Clock clock;
  private final IdGenerator idGen;
  private final Duration holdDuration;

  public HoldSeatsUseCase(
      UnitOfWork uow,
      SeatStateRepository seatStateRepo,
      ReservationRepository reservationRepo,
      ScreeningCounterRepository screeningCounterRepo,
      Clock clock,
      IdGenerator idGen,
      Duration holdDuration) {
    super(uow);
    this.seatStateRepo = Objects.requireNonNull(seatStateRepo, "seatStateRepo");
    this.reservationRepo = Objects.requireNonNull(reservationRepo, "reservationRepo");
    this.screeningCounterRepo =
        Objects.requireNonNull(screeningCounterRepo, "screeningCounterRepo");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.idGen = Objects.requireNonNull(idGen, "idGen");
    this.holdDuration = Objects.requireNonNull(holdDuration, "holdDuration");
  }

  public record Command(UserId userId, ScreeningId screeningId, List<SeatId> seats) {
    public Command {
      Objects.requireNonNull(userId, "userId");
      Objects.requireNonNull(screeningId, "screeningId");
      seats = List.copyOf(seats);
      if (seats.isEmpty()) {
        throw new IllegalArgumentException("seats must not be empty");
      }
      if (seats.size() > 8) {
        throw new IllegalArgumentException("max 8 seats per HOLD");
      }
    }
  }

  public record Result(ReservationId reservationId, Instant expiresAt) {}

  @Override
  protected Result handle(Command cmd) {
    Instant now = clock.now();
    Instant expiresAt = now.plus(holdDuration);
    ReservationId reservationId = new ReservationId(idGen.newId());

    reservationRepo.save(
        new Reservation(
            reservationId,
            cmd.userId(),
            cmd.screeningId(),
            ReservationStatus.HOLD,
            expiresAt,
            now,
            now,
            0));

    int affected =
        seatStateRepo.tryHold(cmd.screeningId(), cmd.seats(), reservationId, expiresAt, now);
    if (affected != cmd.seats().size()) {
      throw new ConflictException(
          "Some seats are not AVAILABLE: requested=" + cmd.seats().size() + " held=" + affected);
    }

    screeningCounterRepo.adjust(cmd.screeningId(), -cmd.seats().size(), cmd.seats().size(), 0, now);

    return new Result(reservationId, expiresAt);
  }
}
