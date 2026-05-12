package com.theater.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.reservation.domain.Reservation;
import com.theater.reservation.domain.ReservationRepository;
import com.theater.reservation.domain.ReservationStatus;
import com.theater.reservation.domain.ScreeningCounterRepository;
import com.theater.reservation.domain.SeatStateRepository;
import com.theater.reservation.domain.SeatStateStatus;
import com.theater.reservation.infrastructure.ReservationModule;
import com.theater.shared.kernel.ReservationId;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExpireHoldsJobAtomicityTxTest {

  private Db.TestDb testDb;
  private JdbcUnitOfWork uow;
  private ReservationRepository reservationRepo;
  private ExpireHoldsJob job;

  @BeforeEach
  void setup() {
    testDb = Db.openTempFile();
    uow = new JdbcUnitOfWork(testDb.writable(), testDb.readOnly());
    var container = new com.theater.shared.di.Container();
    container.registerSingleton(UnitOfWork.class, c -> uow);
    container.install(new ReservationModule());
    reservationRepo = container.resolve(ReservationRepository.class);
    job =
        new ExpireHoldsJob(
            uow,
            new ThrowOnSaveReservationRepository(reservationRepo),
            container.resolve(SeatStateRepository.class),
            container.resolve(ScreeningCounterRepository.class),
            FixedClock.at(ExpireHoldsJobTxTest.NOW));
    seed();
  }

  @AfterEach
  void teardown() {
    testDb.close();
  }

  @Test
  void exception_after_seat_release_rolls_back_seats_reservation_and_counters() {
    assertThatThrownBy(() -> job.run())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("injected failure on reservation save");

    assertThat(reservationStatus(ExpireHoldsJobTxTest.EXPIRED_ID))
        .isEqualTo(ReservationStatus.HOLD);
    assertThat(statusCounts()).containsEntry(SeatStateStatus.HOLD, 3L);
    assertThat(screeningCounter("available_seat_count")).isZero();
    assertThat(screeningCounter("reserved_seat_count")).isEqualTo(3);
  }

  private void seed() {
    uow.executeVoid(
        Tx.REQUIRED,
        () -> {
          Connection conn = uow.currentConnection();
          ReleaseHoldUseCaseTest.insertUser(conn, "user-a");
          ExpireHoldsJobTxTest.insertCatalog(conn);
          ExpireHoldsJobTxTest.insertReservation(
              conn,
              ExpireHoldsJobTxTest.EXPIRED_ID.value(),
              ExpireHoldsJobTxTest.NOW.minusSeconds(1));
          ExpireHoldsJobTxTest.insertReservation(
              conn,
              ExpireHoldsJobTxTest.ACTIVE_ID.value(),
              ExpireHoldsJobTxTest.NOW.plusSeconds(600));
          ExpireHoldsJobTxTest.insertSeatState(
              conn,
              "A-1",
              ExpireHoldsJobTxTest.EXPIRED_ID.value(),
              ExpireHoldsJobTxTest.NOW.minusSeconds(1));
          ExpireHoldsJobTxTest.insertSeatState(
              conn,
              "A-2",
              ExpireHoldsJobTxTest.EXPIRED_ID.value(),
              ExpireHoldsJobTxTest.NOW.minusSeconds(1));
          ExpireHoldsJobTxTest.insertSeatState(
              conn,
              "A-3",
              ExpireHoldsJobTxTest.ACTIVE_ID.value(),
              ExpireHoldsJobTxTest.NOW.plusSeconds(600));
        });
  }

  private ReservationStatus reservationStatus(ReservationId id) {
    return uow.execute(Tx.READ_ONLY, () -> reservationRepo.findById(id).orElseThrow().status());
  }

  private Map<SeatStateStatus, Long> statusCounts() {
    return uow.execute(
        Tx.READ_ONLY,
        () -> {
          try (PreparedStatement ps =
              uow.currentConnection()
                  .prepareStatement(
                      "SELECT status, COUNT(*) FROM seat_states WHERE screening_id=? GROUP BY status")) {
            ps.setString(1, ExpireHoldsJobTxTest.SCREENING_ID.value());
            try (ResultSet rs = ps.executeQuery()) {
              var counts = new EnumMap<SeatStateStatus, Long>(SeatStateStatus.class);
              while (rs.next()) {
                counts.put(SeatStateStatus.valueOf(rs.getString(1)), rs.getLong(2));
              }
              return counts;
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
          try (var ps =
              uow.currentConnection()
                  .prepareStatement("SELECT " + column + " FROM screenings WHERE screening_id=?")) {
            ps.setString(1, ExpireHoldsJobTxTest.SCREENING_ID.value());
            try (var rs = ps.executeQuery()) {
              rs.next();
              return rs.getInt(1);
            }
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  private static final class ThrowOnSaveReservationRepository implements ReservationRepository {

    private final ReservationRepository delegate;

    ThrowOnSaveReservationRepository(ReservationRepository delegate) {
      this.delegate = delegate;
    }

    @Override
    public Optional<Reservation> findById(ReservationId id) {
      return delegate.findById(id);
    }

    @Override
    public List<Reservation> findActiveByUser(com.theater.shared.kernel.UserId userId) {
      return delegate.findActiveByUser(userId);
    }

    @Override
    public List<ReservationId> findExpiring(Instant now, int limit) {
      return delegate.findExpiring(now, limit);
    }

    @Override
    public void save(Reservation reservation) {
      throw new IllegalStateException("injected failure on reservation save");
    }
  }
}
