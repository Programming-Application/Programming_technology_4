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
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 選択された座席を一定時間 HOLD し、checkout へ渡す予約を作成する。 */
public final class HoldSeatsUseCase
    extends TransactionalUseCase<HoldSeatsUseCase.Command, HoldSeatsUseCase.Result> {

  private static final long HOLD_SECONDS = 15 * 60L;

  private final ReservationRepository reservationRepo;
  private final SeatStateRepository seatStateRepo;
  private final ScreeningCounterRepository screeningCounterRepo;
  private final Clock clock;
  private final IdGenerator ids;

  public HoldSeatsUseCase(
      UnitOfWork uow,
      ReservationRepository reservationRepo,
      SeatStateRepository seatStateRepo,
      ScreeningCounterRepository screeningCounterRepo,
      Clock clock,
      IdGenerator ids) {
    super(uow);
    this.reservationRepo = Objects.requireNonNull(reservationRepo, "reservationRepo");
    this.seatStateRepo = Objects.requireNonNull(seatStateRepo, "seatStateRepo");
    this.screeningCounterRepo =
        Objects.requireNonNull(screeningCounterRepo, "screeningCounterRepo");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ids = Objects.requireNonNull(ids, "ids");
  }

  public record Command(UserId userId, ScreeningId screeningId, List<SeatId> seats) {
    public Command {
      Objects.requireNonNull(userId, "userId");
      Objects.requireNonNull(screeningId, "screeningId");
      seats = List.copyOf(seats);
    }
  }

  public record Result(ReservationId reservationId, Instant expiresAt) {
    public Result {
      Objects.requireNonNull(reservationId, "reservationId");
      Objects.requireNonNull(expiresAt, "expiresAt");
    }
  }

  @Override
  protected void validate(Command cmd) {
    if (cmd.seats().isEmpty()) {
      throw new IllegalArgumentException("seats must not be empty");
    }
  }

  @Override
  protected Result handle(Command cmd) {
    Instant now = clock.now();
    Instant expiresAt = now.plusSeconds(HOLD_SECONDS);
    ReservationId reservationId = new ReservationId(ids.newId());
    Reservation reservation =
        new Reservation(
            reservationId,
            cmd.userId(),
            cmd.screeningId(),
            ReservationStatus.HOLD,
            expiresAt,
            now,
            now,
            0L);

    reservationRepo.save(reservation);
    int held = seatStateRepo.tryHold(cmd.screeningId(), cmd.seats(), reservationId, expiresAt, now);
    if (held != cmd.seats().size()) {
      throw new ConflictException("Selected seats are no longer available");
    }
    screeningCounterRepo.adjust(cmd.screeningId(), -held, held, 0, now);
    return new Result(reservationId, expiresAt);
  }
}
