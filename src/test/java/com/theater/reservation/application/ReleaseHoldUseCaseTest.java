package com.theater.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.reservation.domain.ReservationRepository;
import com.theater.reservation.domain.ReservationStatus;
import com.theater.reservation.domain.ScreeningCounterRepository;
import com.theater.reservation.domain.SeatStateRepository;
import com.theater.reservation.domain.SeatStateStatus;
import com.theater.reservation.infrastructure.ReservationModule;
import com.theater.shared.error.IllegalStateTransitionException;
import com.theater.shared.kernel.ReservationId;
import com.theater.shared.kernel.ScreeningId;
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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReleaseHoldUseCaseTest {

  static final Instant NOW = Instant.parse("2026-05-04T00:00:00Z");
  static final ScreeningId SCREENING_ID = new ScreeningId("screening-1");
  static final ReservationId HOLD_ID = new ReservationId("reservation-hold");
  static final UserId OWNER_ID = new UserId("user-a");

  private Db.TestDb testDb;
  private JdbcUnitOfWork uow;
  private ReleaseHoldUseCase useCase;

  @BeforeEach
  void setup() {
    testDb = Db.openTempFile();
    uow = new JdbcUnitOfWork(testDb.writable(), testDb.readOnly());
    var container = new com.theater.shared.di.Container();
    container.registerSingleton(UnitOfWork.class, c -> uow);
    container.install(new ReservationModule());
    seedHeldReservation();
    useCase =
        new ReleaseHoldUseCase(
            uow,
            container.resolve(ReservationRepository.class),
            container.resolve(SeatStateRepository.class),
            container.resolve(ScreeningCounterRepository.class),
            FixedClock.at(NOW.plusSeconds(60)));
  }

  @AfterEach
  void teardown() {
    testDb.close();
  }

  @Test
  void owner_can_release_hold_and_counters_match_seat_states() {
    useCase.execute(new ReleaseHoldUseCase.Command(OWNER_ID, HOLD_ID));

    var reservation =
        uow.execute(Tx.READ_ONLY, () -> useCaseReservationRepo().findById(HOLD_ID)).orElseThrow();
    assertThat(reservation.status()).isEqualTo(ReservationStatus.CANCELED);
    assertThat(reservation.expiresAt()).isNull();

    assertThat(statusCounts()).containsEntry(SeatStateStatus.AVAILABLE, 3L);
    assertThat(screeningCounter("available_seat_count")).isEqualTo(3);
    assertThat(screeningCounter("reserved_seat_count")).isZero();
    assertThat(screeningCounter("sold_seat_count")).isZero();
  }

  @Test
  void other_user_cannot_release_hold_and_state_is_unchanged() {
    assertThatThrownBy(
            () ->
                useCase.execute(
                    new ReleaseHoldUseCase.Command(new UserId("user-b"), HOLD_ID)))
        .isInstanceOf(IllegalStateTransitionException.class);

    assertThat(reservationStatus(HOLD_ID)).isEqualTo(ReservationStatus.HOLD);
    assertThat(statusCounts()).containsEntry(SeatStateStatus.HOLD, 2L);
    assertThat(screeningCounter("available_seat_count")).isEqualTo(1);
    assertThat(screeningCounter("reserved_seat_count")).isEqualTo(2);
  }

  @Test
  void confirmed_and_canceled_reservations_cannot_be_released() {
    assertThatThrownBy(
            () ->
                useCase.execute(
                    new ReleaseHoldUseCase.Command(
                        OWNER_ID, new ReservationId("reservation-confirmed"))))
        .isInstanceOf(IllegalStateTransitionException.class);

    assertThatThrownBy(
            () ->
                useCase.execute(
                    new ReleaseHoldUseCase.Command(OWNER_ID, new ReservationId("reservation-canceled"))))
        .isInstanceOf(IllegalStateTransitionException.class);
  }

  private ReservationRepository useCaseReservationRepo() {
    var container = new com.theater.shared.di.Container();
    container.registerSingleton(UnitOfWork.class, c -> uow);
    container.install(new ReservationModule());
    return container.resolve(ReservationRepository.class);
  }

  private void seedHeldReservation() {
    uow.executeVoid(
        Tx.REQUIRED,
        () -> {
          Connection conn = uow.currentConnection();
          insertUser(conn, "user-a");
          insertUser(conn, "user-b");
          insertCatalog(conn);
          insertReservation(conn, "reservation-hold", "user-a", ReservationStatus.HOLD);
          insertReservation(conn, "reservation-confirmed", "user-a", ReservationStatus.CONFIRMED);
          insertReservation(conn, "reservation-canceled", "user-a", ReservationStatus.CANCELED);
          insertSeatState(conn, "A-1", SeatStateStatus.HOLD, "reservation-hold");
          insertSeatState(conn, "A-2", SeatStateStatus.HOLD, "reservation-hold");
          insertSeatState(conn, "A-3", SeatStateStatus.AVAILABLE, null);
        });
  }

  private ReservationStatus reservationStatus(ReservationId id) {
    return uow.execute(Tx.READ_ONLY, () -> useCaseReservationRepo().findById(id).orElseThrow().status());
  }

  private Map<SeatStateStatus, Long> statusCounts() {
    return uow.execute(
        Tx.READ_ONLY,
        () -> {
          try (PreparedStatement ps =
              uow.currentConnection()
                  .prepareStatement(
                      "SELECT status, COUNT(*) FROM seat_states WHERE screening_id=? GROUP BY status")) {
            ps.setString(1, SCREENING_ID.value());
            try (ResultSet rs = ps.executeQuery()) {
              var counts = new java.util.EnumMap<SeatStateStatus, Long>(SeatStateStatus.class);
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

  static void insertUser(Connection conn, String id) {
    try (PreparedStatement ps =
        conn.prepareStatement(
            """
            INSERT INTO users(user_id, email, name, password_hash, role, created_at, updated_at, version)
            VALUES (?,?,?,?,?,?,?,0)
            """)) {
      ps.setString(1, id);
      ps.setString(2, id + "@example.com");
      ps.setString(3, id);
      ps.setString(4, "hash");
      ps.setString(5, "USER");
      ps.setLong(6, NOW.toEpochMilli());
      ps.setLong(7, NOW.toEpochMilli());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  static void insertCatalog(Connection conn) {
    try {
      execute(
          conn,
          "INSERT INTO movies(movie_id,title,description,duration_minutes,is_published,created_at,updated_at,version)"
              + " VALUES ('movie-1','River Line','',120,1,1,1,0)");
      execute(
          conn,
          "INSERT INTO screens(screen_id,name,total_seats,created_at,updated_at)"
              + " VALUES ('screen-1','Screen 1',3,1,1)");
      int number = 1;
      for (String seat : List.of("A-1", "A-2", "A-3")) {
        execute(
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
        ps.setInt(10, 1);
        ps.setInt(11, 2);
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

  static void insertReservation(
      Connection conn, String reservationId, String userId, ReservationStatus status) {
    try (PreparedStatement ps =
        conn.prepareStatement(
            """
            INSERT INTO reservations(
              reservation_id, user_id, screening_id, status, expires_at, created_at, updated_at, version)
            VALUES (?,?,?,?,?,?,?,0)
            """)) {
      ps.setString(1, reservationId);
      ps.setString(2, userId);
      ps.setString(3, SCREENING_ID.value());
      ps.setString(4, status.name());
      if (status == ReservationStatus.HOLD) {
        ps.setLong(5, NOW.plusSeconds(600).toEpochMilli());
      } else {
        ps.setObject(5, null);
      }
      ps.setLong(6, NOW.toEpochMilli());
      ps.setLong(7, NOW.toEpochMilli());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  static void insertSeatState(
      Connection conn, String seatId, SeatStateStatus status, String reservationId) {
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
      ps.setString(3, status.name());
      ps.setString(4, reservationId);
      if (status == SeatStateStatus.HOLD) {
        ps.setLong(5, NOW.plusSeconds(600).toEpochMilli());
      } else {
        ps.setObject(5, null);
      }
      ps.setObject(6, null);
      ps.setInt(7, 1800);
      ps.setLong(8, 0);
      ps.setLong(9, NOW.toEpochMilli());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  static void execute(Connection conn, String sql) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.executeUpdate();
    }
  }
}
