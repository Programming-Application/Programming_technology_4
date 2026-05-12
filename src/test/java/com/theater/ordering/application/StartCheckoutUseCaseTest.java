package com.theater.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.reservation.domain.Reservation;
import com.theater.reservation.domain.ReservationRepository;
import com.theater.reservation.domain.ReservationStatus;
import com.theater.reservation.domain.SeatState;
import com.theater.reservation.domain.SeatStateRepository;
import com.theater.reservation.domain.SeatStateStatus;
import com.theater.shared.error.IllegalStateTransitionException;
import com.theater.shared.error.NotFoundException;
import com.theater.shared.kernel.Currency;
import com.theater.shared.kernel.Money;
import com.theater.shared.kernel.ReservationId;
import com.theater.shared.kernel.ScreeningId;
import com.theater.shared.kernel.SeatId;
import com.theater.shared.kernel.TicketId;
import com.theater.shared.kernel.UserId;
import com.theater.shared.tx.Tx;
import com.theater.shared.tx.UnitOfWork;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

final class StartCheckoutUseCaseTest {

  private static final Instant NOW = Instant.parse("2026-05-12T00:00:00Z");
  private static final ReservationId RESERVATION_ID = new ReservationId("reservation-1");
  private static final ScreeningId SCREENING_ID = new ScreeningId("screening-1");
  private static final UserId USER_ID = new UserId("user-1");

  private final FakeUnitOfWork uow = new FakeUnitOfWork();
  private final FakeReservationRepository reservationRepo = new FakeReservationRepository();
  private final FakeSeatStateRepository seatStateRepo = new FakeSeatStateRepository();
  private final StartCheckoutUseCase useCase =
      new StartCheckoutUseCase(uow, reservationRepo, seatStateRepo, () -> NOW);

  @Test
  void returns_hold_reservation_summary_with_money_total_in_read_only_tx() {
    reservationRepo.reservation = Optional.of(holdReservation(USER_ID, NOW.plusSeconds(60)));
    SeatState a = heldSeat("A-1", RESERVATION_ID, 1_800);
    SeatState b = heldSeat("A-2", RESERVATION_ID, 2_200);
    seatStateRepo.seats = List.of(a, b, heldSeat("A-3", new ReservationId("reservation-2"), 9_999));

    CheckoutSummary summary =
        useCase.execute(new StartCheckoutUseCase.Command(RESERVATION_ID, USER_ID));

    assertThat(uow.lastMode()).isEqualTo(Tx.READ_ONLY);
    assertThat(summary.reservationId()).isEqualTo(RESERVATION_ID);
    assertThat(summary.screeningId()).isEqualTo(SCREENING_ID);
    assertThat(summary.seats()).containsExactly(a, b);
    assertThat(summary.total()).isEqualTo(new Money(4_000, Currency.JPY));
  }

  @Test
  void missing_reservation_throws_not_found() {
    reservationRepo.reservation = Optional.empty();

    assertThatThrownBy(
            () -> useCase.execute(new StartCheckoutUseCase.Command(RESERVATION_ID, USER_ID)))
        .isInstanceOf(NotFoundException.class)
        .hasMessageContaining("Reservation");
  }

  @Test
  void other_users_reservation_is_rejected() {
    reservationRepo.reservation =
        Optional.of(holdReservation(new UserId("other-user"), NOW.plusSeconds(60)));

    assertThatThrownBy(
            () -> useCase.execute(new StartCheckoutUseCase.Command(RESERVATION_ID, USER_ID)))
        .isInstanceOf(IllegalStateTransitionException.class);
  }

  @Test
  void expired_hold_is_rejected() {
    reservationRepo.reservation = Optional.of(holdReservation(USER_ID, NOW.minusSeconds(1)));

    assertThatThrownBy(
            () -> useCase.execute(new StartCheckoutUseCase.Command(RESERVATION_ID, USER_ID)))
        .isInstanceOf(IllegalStateTransitionException.class)
        .hasMessageContaining("EXPIRED");
  }

  private static Reservation holdReservation(UserId userId, Instant expiresAt) {
    return new Reservation(
        RESERVATION_ID, userId, SCREENING_ID, ReservationStatus.HOLD, expiresAt, NOW, NOW, 0);
  }

  private static SeatState heldSeat(String seatId, ReservationId reservationId, int price) {
    return new SeatState(
        SCREENING_ID,
        new SeatId(seatId),
        SeatStateStatus.HOLD,
        reservationId,
        NOW.plusSeconds(60),
        null,
        price,
        0,
        NOW);
  }

  private static final class FakeUnitOfWork implements UnitOfWork {
    private Tx lastMode;

    @Override
    public <R> R execute(Tx mode, Supplier<R> work) {
      lastMode = mode;
      return work.get();
    }

    @Override
    public Connection currentConnection() {
      throw new UnsupportedOperationException("test fake does not expose a JDBC connection");
    }

    Tx lastMode() {
      return lastMode;
    }
  }

  private static final class FakeReservationRepository implements ReservationRepository {
    private Optional<Reservation> reservation = Optional.empty();

    @Override
    public Optional<Reservation> findById(ReservationId id) {
      return reservation.filter(r -> r.id().equals(id));
    }

    @Override
    public List<Reservation> findActiveByUser(UserId userId) {
      return List.of();
    }

    @Override
    public List<ReservationId> findExpiring(Instant now, int limit) {
      return List.of();
    }

    @Override
    public void save(Reservation reservation) {}
  }

  private static final class FakeSeatStateRepository implements SeatStateRepository {
    private List<SeatState> seats = List.of();

    @Override
    public List<SeatState> findByScreening(ScreeningId screeningId) {
      return seats.stream().filter(s -> s.screeningId().equals(screeningId)).toList();
    }

    @Override
    public int tryHold(
        ScreeningId screeningId,
        List<SeatId> seats,
        ReservationId reservationId,
        Instant expiresAt,
        Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int releaseByReservation(ReservationId reservationId, Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void markSold(
        ReservationId reservationId, Map<SeatId, TicketId> seatToTicket, Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void markExpired(List<ReservationId> reservationIds, Instant now) {
      throw new UnsupportedOperationException();
    }
  }
}
