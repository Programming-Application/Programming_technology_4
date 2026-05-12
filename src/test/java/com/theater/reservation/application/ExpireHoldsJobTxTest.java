package com.theater.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.theater.reservation.domain.ReservationRepository;
import com.theater.reservation.domain.ReservationStatus;
import com.theater.reservation.domain.ScreeningCounterRepository;
import com.theater.reservation.domain.SeatStateRepository;
import com.theater.reservation.domain.SeatStateStatus;
import com.theater.reservation.infrastructure.ReservationModule;
import com.theater.shared.kernel.ReservationId;
import com.theater.shared.kernel.ScreeningId;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExpireHoldsJobTxTest {

  static final Instant NOW = Instant.parse("2026-05-04T00:00:00Z");
  static final ScreeningId SCREENING_ID = new ScreeningId("screening-1");
  static final ReservationId EXPIRED_ID = new ReservationId("reservation-expired");
  static final ReservationId ACTIVE_ID = new ReservationId("reservation-active");

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
            reservationRepo,
            container.resolve(SeatStateRepository.class),
            container.resolve(ScreeningCounterRepository.class),
            FixedClock.at(NOW));
    seed();
  }

  @AfterEach
  void teardown() {
    testDb.close();
  }

  @Test
  void expired_holds_are_released_and_active_holds_are_kept() {
    job.run();

    assertThat(reservationStatus(EXPIRED_ID)).isEqualTo(ReservationStatus.EXPIRED);
    assertThat(reservationExpiresAt(EXPIRED_ID)).isNull();
    assertThat(reservationStatus(ACTIVE_ID)).isEqualTo(ReservationStatus.HOLD);

    assertThat(statusCounts())
        .containsEntry(SeatStateStatus.AVAILABLE, 2L)
        .containsEntry(SeatStateStatus.HOLD, 1L);
    assertThat(screeningCounter("available_seat_count")).isEqualTo(2);
    assertThat(screeningCounter("reserved_seat_count")).isEqualTo(1);
    assertThat(screeningCounter("sold_seat_count")).isZero();
  }

  @Test
  void running_twice_is_a_noop_after_first_expiration() {
    job.run();
    var firstCounts = statusCounts();
    int firstAvailable = screeningCounter("available_seat_count");
    int firstReserved = screeningCounter("reserved_seat_count");

    job.run();

    assertThat(statusCounts()).isEqualTo(firstCounts);
    assertThat(screeningCounter("available_seat_count")).isEqualTo(firstAvailable);
    assertThat(screeningCounter("reserved_seat_count")).isEqualTo(firstReserved);
  }

  private void seed() {
    uow.executeVoid(
        Tx.REQUIRED,
        () -> {
          Connection conn = uow.currentConnection();
          ReleaseHoldUseCaseTest.insertUser(conn, "user-a");
          insertCatalog(conn);
          insertReservation(conn, EXPIRED_ID.value(), NOW.minusSeconds(1));
          insertReservation(conn, ACTIVE_ID.value(), NOW.plusSeconds(600));
          insertSeatState(conn, "A-1", EXPIRED_ID.value(), NOW.minusSeconds(1));
          insertSeatState(conn, "A-2", EXPIRED_ID.value(), NOW.minusSeconds(1));
          insertSeatState(conn, "A-3", ACTIVE_ID.value(), NOW.plusSeconds(600));
        });
  }

  ReservationStatus reservationStatus(ReservationId id) {
    return uow.execute(Tx.READ_ONLY, () -> reservationRepo.findById(id).orElseThrow().status());
  }

  Instant reservationExpiresAt(ReservationId id) {
    return uow.execute(Tx.READ_ONLY, () -> reservationRepo.findById(id).orElseThrow().expiresAt());
  }

  Map<SeatStateStatus, Long> statusCounts() {
    return uow.execute(
        Tx.READ_ONLY,
        () -> {
          try (PreparedStatement ps =
              uow.currentConnection()
                  .prepareStatement(
                      "SELECT status, COUNT(*) FROM seat_states WHERE screening_id=? GROUP BY status")) {
            ps.setString(1, SCREENING_ID.value());
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

  int screeningCounter(String column) {
    return uow.execute(
        Tx.READ_ONLY,
        () -> {
          try (PreparedStatement ps =
              uow.currentConnection()
                  .prepareStatement("SELECT " + column + " FROM screenings WHERE screening_id=?")) {
            ps.setString(1, SCREENING_ID.value());
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              return rs.getInt(1);
            }
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  static void insertCatalog(Connection conn) {
    try {
      ReleaseHoldUseCaseTest.execute(
          conn,
          "INSERT INTO movies(movie_id,title,description,duration_minutes,is_published,created_at,updated_at,version)"
              + " VALUES ('movie-1','River Line','',120,1,1,1,0)");
      ReleaseHoldUseCaseTest.execute(
          conn,
          "INSERT INTO screens(screen_id,name,total_seats,created_at,updated_at)"
              + " VALUES ('screen-1','Screen 1',3,1,1)");
      int number = 1;
      for (String seat : List.of("A-1", "A-2", "A-3")) {
        ReleaseHoldUseCaseTest.execute(
            conn,
            "INSERT INTO seats(screen_id,seat_id,row,number,seat_type,is_active)"
                + " VALUES ('screen-1','"
                + seat
                + "','A',"
                + number
                + ",'NORMAL',1)");
        number++;
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
        ps.setLong(4, NOW.plusSeconds(3_600).toEpochMilli());
        ps.setLong(5, NOW.plusSeconds(7_200).toEpochMilli());
        ps.setLong(6, NOW.minusSeconds(3_600).toEpochMilli());
        ps.setLong(7, NOW.plusSeconds(1_800).toEpochMilli());
        ps.setString(8, "OPEN");
        ps.setInt(9, 0);
        ps.setInt(10, 0);
        ps.setInt(11, 3);
        ps.setInt(12, 0);
        ps.setLong(13, NOW.toEpochMilli());
        ps.setLong(14, NOW.toEpochMilli());
        ps.setLong(15, NOW.toEpochMilli());
        ps.executeUpdate();
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  static void insertReservation(Connection conn, String reservationId, Instant expiresAt) {
    try (PreparedStatement ps =
        conn.prepareStatement(
            """
            INSERT INTO reservations(
              reservation_id, user_id, screening_id, status, expires_at, created_at, updated_at, version)
            VALUES (?,?,?,?,?,?,?,0)
            """)) {
      ps.setString(1, reservationId);
      ps.setString(2, "user-a");
      ps.setString(3, SCREENING_ID.value());
      ps.setString(4, ReservationStatus.HOLD.name());
      ps.setLong(5, expiresAt.toEpochMilli());
      ps.setLong(6, NOW.toEpochMilli());
      ps.setLong(7, NOW.toEpochMilli());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  static void insertSeatState(
      Connection conn, String seatId, String reservationId, Instant holdExpiresAt) {
    try (PreparedStatement ps =
        conn.prepareStatement(
            """
            INSERT INTO seat_states(
              screening_id, seat_id, status, reservation_id, hold_expires_at,
              ticket_id, price, version, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?)
            """)) {
      ps.setString(1, SCREENING_ID.value());
      ps.setString(2, seatId);
      ps.setString(3, SeatStateStatus.HOLD.name());
      ps.setString(4, reservationId);
      ps.setLong(5, holdExpiresAt.toEpochMilli());
      ps.setObject(6, null);
      ps.setInt(7, 1800);
      ps.setLong(8, 0);
      ps.setLong(9, NOW.toEpochMilli());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
