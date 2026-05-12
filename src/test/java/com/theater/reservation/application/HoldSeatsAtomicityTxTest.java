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
import com.theater.shared.kernel.UserId;
import com.theater.shared.tx.JdbcUnitOfWork;
import com.theater.shared.tx.Tx;
import com.theater.shared.tx.UnitOfWork;
import com.theater.testkit.Db;
import com.theater.testkit.FixedClock;
import com.theater.testkit.IncrementingIdGenerator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** HoldSeats の Atomicity (原子性) テスト。 */
class HoldSeatsAtomicityTxTest {

  static final Instant NOW = Instant.parse("2026-05-04T00:00:00Z");
  static final ScreeningId SCREENING_ID = new ScreeningId("screening-1");
  static final UserId USER_A = new UserId("user-a");
  static final List<SeatId> SEATS = List.of(new SeatId("A-1"), new SeatId("A-2"));

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
  void exception_in_tryHold_rolls_back_reservation_and_counters() {
    var failingSeatRepo = new ThrowOnTryHoldSeatStateRepository(seatStateRepo);
    var useCase = buildUseCase(failingSeatRepo, reservationRepo, screeningCounterRepo);

    var before = Db.snapshot(testDb.writable());
    assertThatThrownBy(() -> useCase.execute(cmd()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("injected failure in tryHold");
    var after = Db.snapshot(testDb.writable());
    assertThat(after).isEqualTo(before);
  }

  @Test
  void exception_in_counter_adjust_rolls_back_reservation_and_seat_states() {
    var failingCounterRepo = new ThrowOnAdjustScreeningCounterRepository(screeningCounterRepo);
    var useCase = buildUseCase(seatStateRepo, reservationRepo, failingCounterRepo);

    var before = Db.snapshot(testDb.writable());
    assertThatThrownBy(() -> useCase.execute(cmd()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("injected failure in adjust");
    var after = Db.snapshot(testDb.writable());
    assertThat(after).isEqualTo(before);
  }

  @Test
  void conflict_exception_rolls_back_reservation_when_seat_already_held() {
    // A-1 はすでに HOLD 済み → tryHold が 1 しか返せず ConflictException → 全 rollback
    holdSeatA1();

    var before = Db.snapshot(testDb.writable());
    assertThatThrownBy(
            () -> buildUseCase(seatStateRepo, reservationRepo, screeningCounterRepo).execute(cmd()))
        .isInstanceOf(com.theater.shared.error.ConflictException.class);

    var after = Db.snapshot(testDb.writable());
    // reservations は変化なし (rollback で元に戻る)
    assertThat(after.get("reservations")).isEqualTo(before.get("reservations"));
  }

  private HoldSeatsUseCase buildUseCase(
      SeatStateRepository seats,
      ReservationRepository reservations,
      ScreeningCounterRepository counters) {
    return new HoldSeatsUseCase(
        uow,
        seats,
        reservations,
        counters,
        FixedClock.at(NOW),
        new IncrementingIdGenerator("rid-"),
        HoldSeatsUseCase.DEFAULT_HOLD_DURATION);
  }

  private HoldSeatsUseCase.Command cmd() {
    return new HoldSeatsUseCase.Command(USER_A, SCREENING_ID, SEATS);
  }

  private void holdSeatA1() {
    uow.executeVoid(
        Tx.REQUIRED,
        () -> {
          Connection conn = uow.currentConnection();
          ReleaseHoldUseCaseTest.insertReservation(
              conn, "existing-reservation", "user-a", ReservationStatus.HOLD);
          // A-1 は seed() で AVAILABLE として挿入済み → UPDATE で HOLD に変更
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "UPDATE seat_states SET status='HOLD', reservation_id=?,"
                      + " hold_expires_at=? WHERE screening_id=? AND seat_id='A-1'")) {
            ps.setString(1, "existing-reservation");
            ps.setLong(2, NOW.plusSeconds(600).toEpochMilli());
            ps.setString(3, SCREENING_ID.value());
            ps.executeUpdate();
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  private void seed() {
    uow.executeVoid(
        Tx.REQUIRED,
        () -> {
          Connection conn = uow.currentConnection();
          ReleaseHoldUseCaseTest.insertUser(conn, "user-a");
          insertCatalogWithAllAvailable(conn);
        });
  }

  static void insertCatalogWithAllAvailable(Connection conn) {
    try {
      ReleaseHoldUseCaseTest.execute(
          conn,
          "INSERT INTO movies(movie_id,title,description,duration_minutes,is_published,"
              + "created_at,updated_at,version) VALUES ('movie-1','Test','',120,1,1,1,0)");
      ReleaseHoldUseCaseTest.execute(
          conn,
          "INSERT INTO screens(screen_id,name,total_seats,created_at,updated_at)"
              + " VALUES ('screen-1','Screen 1',3,1,1)");
      int n = 1;
      for (String s : List.of("A-1", "A-2", "A-3")) {
        ReleaseHoldUseCaseTest.execute(
            conn,
            "INSERT INTO seats(screen_id,seat_id,row,number,seat_type,is_active)"
                + " VALUES ('screen-1','"
                + s
                + "','A',"
                + n
                + ",'NORMAL',1)");
        n++;
      }
      try (PreparedStatement ps =
          conn.prepareStatement(
              """
              INSERT INTO screenings(
                screening_id, movie_id, screen_id, start_time, end_time,
                sales_start_at, sales_end_at, status, is_private,
                available_seat_count, reserved_seat_count, sold_seat_count,
                last_updated, created_at, updated_at, version)
              VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)
              """)) {
        ps.setString(1, SCREENING_ID.value());
        ps.setString(2, "movie-1");
        ps.setString(3, "screen-1");
        ps.setLong(4, NOW.plusSeconds(3600).toEpochMilli());
        ps.setLong(5, NOW.plusSeconds(7200).toEpochMilli());
        ps.setLong(6, NOW.minusSeconds(3600).toEpochMilli());
        ps.setLong(7, NOW.plusSeconds(1800).toEpochMilli());
        ps.setString(8, "OPEN");
        ps.setInt(9, 0);
        ps.setInt(10, 3); // all available
        ps.setInt(11, 0);
        ps.setInt(12, 0);
        ps.setLong(13, NOW.toEpochMilli());
        ps.setLong(14, NOW.toEpochMilli());
        ps.setLong(15, NOW.toEpochMilli());
        ps.executeUpdate();
      }
      for (String s : List.of("A-1", "A-2", "A-3")) {
        ReleaseHoldUseCaseTest.insertSeatState(conn, s, SeatStateStatus.AVAILABLE, null);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  // ── Test doubles ────────────────────────────────────────────────────────

  private static final class ThrowOnTryHoldSeatStateRepository implements SeatStateRepository {
    private final SeatStateRepository delegate;

    ThrowOnTryHoldSeatStateRepository(SeatStateRepository delegate) {
      this.delegate = delegate;
    }

    @Override
    public List<com.theater.reservation.domain.SeatState> findByScreening(ScreeningId sid) {
      return delegate.findByScreening(sid);
    }

    @Override
    public int tryHold(
        ScreeningId sid, List<SeatId> seats, ReservationId rid, Instant expiresAt, Instant now) {
      throw new IllegalStateException("injected failure in tryHold");
    }

    @Override
    public int releaseByReservation(ReservationId rid, Instant now) {
      return delegate.releaseByReservation(rid, now);
    }

    @Override
    public void markSold(ReservationId rid, Map<SeatId, TicketId> seatToTicket, Instant now) {
      delegate.markSold(rid, seatToTicket, now);
    }

    @Override
    public void markExpired(List<ReservationId> ids, Instant now) {
      delegate.markExpired(ids, now);
    }
  }

  private static final class ThrowOnAdjustScreeningCounterRepository
      implements ScreeningCounterRepository {
    private final ScreeningCounterRepository delegate;

    ThrowOnAdjustScreeningCounterRepository(ScreeningCounterRepository delegate) {
      this.delegate = delegate;
    }

    @Override
    public void adjust(ScreeningId sid, int availDelta, int resvDelta, int soldDelta, Instant now) {
      throw new IllegalStateException("injected failure in adjust");
    }
  }
}
