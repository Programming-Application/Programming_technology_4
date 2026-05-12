package com.theater.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.theater.reservation.domain.ReservationRepository;
import com.theater.reservation.domain.ScreeningCounterRepository;
import com.theater.reservation.domain.SeatStateRepository;
import com.theater.reservation.domain.SeatStateStatus;
import com.theater.reservation.infrastructure.ReservationModule;
import com.theater.shared.error.ConflictException;
import com.theater.shared.kernel.ScreeningId;
import com.theater.shared.kernel.SeatId;
import com.theater.shared.kernel.UserId;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** HoldSeats の Isolation (同時実行) テスト。 */
@Tag("concurrency")
class HoldSeatsConcurrencyTest {

  static final Instant NOW = Instant.parse("2026-05-04T00:00:00Z");
  static final ScreeningId SCREENING_ID = new ScreeningId("screening-1");

  private Db.TestDb testDb;
  private JdbcUnitOfWork uow;
  private HoldSeatsUseCase useCase;

  /** スレッドセーフな連番 ID 生成器。 */
  private final AtomicLong idSeq = new AtomicLong(0);

  @BeforeEach
  void setup() {
    testDb = Db.openTempFile();
    uow = new JdbcUnitOfWork(testDb.writable(), testDb.readOnly());
    var container = new com.theater.shared.di.Container();
    container.registerSingleton(UnitOfWork.class, c -> uow);
    container.install(new ReservationModule());
    useCase =
        new HoldSeatsUseCase(
            uow,
            container.resolve(SeatStateRepository.class),
            container.resolve(ReservationRepository.class),
            container.resolve(ScreeningCounterRepository.class),
            FixedClock.at(NOW),
            () -> "rid-" + idSeq.incrementAndGet(),
            HoldSeatsUseCase.DEFAULT_HOLD_DURATION);
  }

  @AfterEach
  void teardown() {
    testDb.close();
  }

  @RepeatedTest(20)
  void two_users_holding_same_seat_only_one_succeeds() throws Exception {
    seedUsers(2);
    seedScreeningWithSeats(List.of("A-1"), 1);

    var barrier = new CyclicBarrier(2);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var f1 = pool.submit(() -> safeHold(barrier, "user-1", List.of(new SeatId("A-1"))));
      var f2 = pool.submit(() -> safeHold(barrier, "user-2", List.of(new SeatId("A-1"))));

      Boolean r1 = f1.get(15, TimeUnit.SECONDS);
      Boolean r2 = f2.get(15, TimeUnit.SECONDS);

      long successes = List.of(r1, r2).stream().filter(Boolean.TRUE::equals).count();
      long failures = List.of(r1, r2).stream().filter(Boolean.FALSE::equals).count();

      assertThat(successes).isEqualTo(1);
      assertThat(failures).isEqualTo(1);
      assertThat(holdCount()).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @Tag("slow")
  void hundred_users_competing_for_50_seats_exactly_50_succeed() throws Exception {
    int seats = 50;
    int users = 100;
    seedUsers(users);
    List<String> seatIds = new ArrayList<>();
    for (int i = 1; i <= seats; i++) {
      seatIds.add("S-" + i);
    }
    seedScreeningWithSeats(seatIds, seats);

    var barrier = new CyclicBarrier(users);
    var pool = Executors.newFixedThreadPool(users);
    try {
      List<Future<Boolean>> futures = new ArrayList<>();
      for (int i = 1; i <= users; i++) {
        // ユーザ i は座席 S-(i%50+1) を狙う → 各座席に 2 ユーザが競合
        final String userId = "user-" + i;
        final String targetSeat = "S-" + (((i - 1) % seats) + 1);
        futures.add(pool.submit(() -> safeHold(barrier, userId, List.of(new SeatId(targetSeat)))));
      }

      long successes = 0;
      for (Future<Boolean> f : futures) {
        if (Boolean.TRUE.equals(f.get(30, TimeUnit.SECONDS))) {
          successes++;
        }
      }

      assertThat(successes).isEqualTo(seats);
      assertThat(holdCount()).isEqualTo(seats);
      assertThat(availableCount()).isZero();
    } finally {
      pool.shutdownNow();
    }
  }

  /** バリア同期後に HoldSeats を実行する。成功なら true、ConflictException なら false。 */
  private boolean safeHold(CyclicBarrier barrier, String userId, List<SeatId> seats) {
    try {
      barrier.await(15, TimeUnit.SECONDS);
      useCase.execute(new HoldSeatsUseCase.Command(new UserId(userId), SCREENING_ID, seats));
      return true;
    } catch (ConflictException e) {
      return false;
    } catch (Exception e) {
      throw new IllegalStateException("Unexpected error in thread for " + userId, e);
    }
  }

  private void seedUsers(int count) {
    uow.executeVoid(
        Tx.REQUIRED,
        () -> {
          Connection conn = uow.currentConnection();
          for (int i = 1; i <= count; i++) {
            ReleaseHoldUseCaseTest.insertUser(conn, "user-" + i);
          }
        });
  }

  private void seedScreeningWithSeats(List<String> seatIds, int totalSeats) {
    uow.executeVoid(
        Tx.REQUIRED,
        () -> {
          Connection conn = uow.currentConnection();
          try {
            ReleaseHoldUseCaseTest.execute(
                conn,
                "INSERT INTO movies(movie_id,title,description,duration_minutes,"
                    + "is_published,created_at,updated_at,version)"
                    + " VALUES ('movie-1','Test','',120,1,1,1,0)");
            ReleaseHoldUseCaseTest.execute(
                conn,
                "INSERT INTO screens(screen_id,name,total_seats,created_at,updated_at)"
                    + " VALUES ('screen-1','Screen 1',"
                    + totalSeats
                    + ",1,1)");
            int n = 1;
            for (String sid : seatIds) {
              ReleaseHoldUseCaseTest.execute(
                  conn,
                  "INSERT INTO seats(screen_id,seat_id,row,number,seat_type,is_active)"
                      + " VALUES ('screen-1','"
                      + sid
                      + "','S',"
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
              ps.setInt(10, totalSeats);
              ps.setInt(11, 0);
              ps.setInt(12, 0);
              ps.setLong(13, NOW.toEpochMilli());
              ps.setLong(14, NOW.toEpochMilli());
              ps.setLong(15, NOW.toEpochMilli());
              ps.executeUpdate();
            }
            for (String sid : seatIds) {
              ReleaseHoldUseCaseTest.insertSeatState(
                  conn, sid, com.theater.reservation.domain.SeatStateStatus.AVAILABLE, null);
            }
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  private long holdCount() {
    return statusCount(SeatStateStatus.HOLD);
  }

  private long availableCount() {
    return statusCount(SeatStateStatus.AVAILABLE);
  }

  private long statusCount(SeatStateStatus status) {
    return uow.execute(
        Tx.READ_ONLY,
        () -> {
          try (PreparedStatement ps =
              uow.currentConnection()
                  .prepareStatement(
                      "SELECT COUNT(*) FROM seat_states" + " WHERE screening_id=? AND status=?")) {
            ps.setString(1, SCREENING_ID.value());
            ps.setString(2, status.name());
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              return rs.getLong(1);
            }
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }
}
