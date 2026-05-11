package com.theater.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.reservation.domain.ReservationRepository;
import com.theater.reservation.domain.ReservationStatus;
import com.theater.reservation.domain.ScreeningCounterRepository;
import com.theater.reservation.domain.SeatStateRepository;
import com.theater.reservation.domain.SeatStateStatus;
import com.theater.reservation.infrastructure.ReservationModule;
import com.theater.shared.kernel.ReservationId;
import com.theater.shared.kernel.ScreeningId;
import com.theater.shared.kernel.SeatId;
import com.theater.shared.kernel.TicketId;
import com.theater.shared.tx.JdbcUnitOfWork;
import com.theater.shared.tx.Tx;
import com.theater.shared.tx.UnitOfWork;
import com.theater.testkit.Db;
import com.theater.testkit.FixedClock;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReleaseHoldAtomicityTxTest {

  private Db.TestDb testDb;
  private JdbcUnitOfWork uow;
  private ReservationRepository reservationRepo;
  private SeatStateRepository seatStateRepo;
  private ScreeningCounterRepository screeningCounterRepo;

  @BeforeEach
  void setup() {
    testDb = Db.openTempFile();
    uow = new JdbcUnitOfWork(testDb.writable(), testDb.readOnly());
    var container = new com.theater.shared.di.Container();
    container.registerSingleton(UnitOfWork.class, c -> uow);
    container.install(new ReservationModule());
    reservationRepo = container.resolve(ReservationRepository.class);
    seatStateRepo = container.resolve(SeatStateRepository.class);
    screeningCounterRepo = container.resolve(ScreeningCounterRepository.class);
    seed();
  }

  @AfterEach
  void teardown() {
    testDb.close();
  }

  @Test
  void exception_after_seat_release_rolls_back_seats_reservation_and_counters() {
    var failingSeatRepo = new ThrowAfterReleaseSeatStateRepository(seatStateRepo);
    var useCase =
        new ReleaseHoldUseCase(
            uow,
            reservationRepo,
            failingSeatRepo,
            screeningCounterRepo,
            FixedClock.at(ReleaseHoldUseCaseTest.NOW.plusSeconds(60)));

    assertThatThrownBy(
            () ->
                useCase.execute(
                    new ReleaseHoldUseCase.Command(
                        ReleaseHoldUseCaseTest.OWNER_ID, ReleaseHoldUseCaseTest.HOLD_ID)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("injected failure after release");

    assertThat(reservationStatus()).isEqualTo(ReservationStatus.HOLD);
    assertThat(heldSeatCount()).isEqualTo(2);
    assertThat(screeningCounter("available_seat_count")).isEqualTo(1);
    assertThat(screeningCounter("reserved_seat_count")).isEqualTo(2);
  }

  private void seed() {
    uow.executeVoid(
        Tx.REQUIRED,
        () -> {
          Connection conn = uow.currentConnection();
          ReleaseHoldUseCaseTest.insertUser(conn, "user-a");
          ReleaseHoldUseCaseTest.insertCatalog(conn);
          ReleaseHoldUseCaseTest.insertReservation(
              conn, "reservation-hold", "user-a", ReservationStatus.HOLD);
          ReleaseHoldUseCaseTest.insertSeatState(
              conn, "A-1", SeatStateStatus.HOLD, "reservation-hold");
          ReleaseHoldUseCaseTest.insertSeatState(
              conn, "A-2", SeatStateStatus.HOLD, "reservation-hold");
          ReleaseHoldUseCaseTest.insertSeatState(conn, "A-3", SeatStateStatus.AVAILABLE, null);
        });
  }

  private ReservationStatus reservationStatus() {
    return uow.execute(
        Tx.READ_ONLY,
        () -> reservationRepo.findById(ReleaseHoldUseCaseTest.HOLD_ID).orElseThrow().status());
  }

  private long heldSeatCount() {
    return uow.execute(
        Tx.READ_ONLY,
        () -> {
          try (PreparedStatement ps =
              uow.currentConnection()
                  .prepareStatement(
                      "SELECT COUNT(*) FROM seat_states WHERE reservation_id=? AND status='HOLD'")) {
            ps.setString(1, ReleaseHoldUseCaseTest.HOLD_ID.value());
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              return rs.getLong(1);
            }
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  private int screeningCounter(String column) {
    return uow.execute(
        Tx.READ_ONLY,
        () -> {
          try (PreparedStatement ps =
              uow.currentConnection()
                  .prepareStatement("SELECT " + column + " FROM screenings WHERE screening_id=?")) {
            ps.setString(1, ReleaseHoldUseCaseTest.SCREENING_ID.value());
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              return rs.getInt(1);
            }
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  private static final class ThrowAfterReleaseSeatStateRepository implements SeatStateRepository {

    private final SeatStateRepository delegate;

    ThrowAfterReleaseSeatStateRepository(SeatStateRepository delegate) {
      this.delegate = delegate;
    }

    @Override
    public List<com.theater.reservation.domain.SeatState> findByScreening(ScreeningId screeningId) {
      return delegate.findByScreening(screeningId);
    }

    @Override
    public int tryHold(
        ScreeningId screeningId,
        List<SeatId> seats,
        ReservationId reservationId,
        Instant expiresAt,
        Instant now) {
      return delegate.tryHold(screeningId, seats, reservationId, expiresAt, now);
    }

    @Override
    public int releaseByReservation(ReservationId reservationId, Instant now) {
      delegate.releaseByReservation(reservationId, now);
      throw new IllegalStateException("injected failure after release");
    }

    @Override
    public void markSold(
        ReservationId reservationId, Map<SeatId, TicketId> seatToTicket, Instant now) {
      delegate.markSold(reservationId, seatToTicket, now);
    }

    @Override
    public void markExpired(List<ReservationId> reservationIds, Instant now) {
      delegate.markExpired(reservationIds, now);
    }
  }
}
