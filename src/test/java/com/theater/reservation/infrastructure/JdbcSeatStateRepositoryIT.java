package com.theater.reservation.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.reservation.domain.SeatStateRepository;
import com.theater.shared.kernel.ReservationId;
import com.theater.shared.kernel.ScreeningId;
import com.theater.shared.tx.JdbcUnitOfWork;
import com.theater.shared.tx.Tx;
import com.theater.testkit.Db;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdbcSeatStateRepositoryIT {

  private static final Instant NOW = Instant.parse("2026-05-12T00:00:00Z");
  private static final Instant EXPIRES = NOW.plusSeconds(900);

  private Db.TestDb testDb;
  private JdbcUnitOfWork uow;
  private SeatStateRepository repository;

  @BeforeEach
  void setup() {
    testDb = Db.openTempFile();
    uow = new JdbcUnitOfWork(testDb.writable(), testDb.readOnly());
    repository = new JdbcSeatStateRepository(uow);
    seedPrerequisites();
  }

  @AfterEach
  void teardown() {
    testDb.close();
  }

  @Test
  void findByScreening_returns_all_seat_states_for_screening() {
    seedAvailableSeat("sc-1", "A-1");
    seedAvailableSeat("sc-1", "A-2");

    var states =
        uow.execute(Tx.READ_ONLY, () -> repository.findByScreening(new ScreeningId("sc-1")));

    assertThat(states).hasSize(2);
    assertThat(states).extracting(s -> s.seatId().value()).containsExactly("A-1", "A-2");
  }

  @Test
  void releaseByReservation_sets_seats_to_available() {
    String reservationId = "rsv-1";
    seedReservation(reservationId);
    seedHeldSeat("sc-1", "A-1", reservationId);
    seedHeldSeat("sc-1", "A-2", reservationId);

    uow.executeVoid(
        Tx.REQUIRED, () -> repository.releaseByReservation(new ReservationId(reservationId)));

    var states =
        uow.execute(Tx.READ_ONLY, () -> repository.findByScreening(new ScreeningId("sc-1")));
    assertThat(states)
        .allMatch(s -> s.status().name().equals("AVAILABLE"))
        .allMatch(s -> s.reservationId() == null)
        .allMatch(s -> s.holdExpiresAt() == null);
  }

  @Test
  void check_constraint_hold_without_reservation_id_throws() {
    assertThatThrownBy(
            () ->
                uow.executeVoid(
                    Tx.REQUIRED,
                    () ->
                        insertRawSeatState(
                            uow.currentConnection(),
                            "sc-1",
                            "B-1",
                            "HOLD",
                            null,
                            EXPIRES.toEpochMilli(),
                            null)))
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(SQLException.class);
  }

  @Test
  void unique_constraint_duplicate_screening_seat_throws() {
    seedAvailableSeat("sc-1", "C-1");

    assertThatThrownBy(() -> uow.executeVoid(Tx.REQUIRED, () -> seedAvailableSeat("sc-1", "C-1")))
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(SQLException.class);
  }

  @Test
  void fk_nonexistent_screening_throws() {
    assertThatThrownBy(
            () ->
                uow.executeVoid(
                    Tx.REQUIRED,
                    () ->
                        insertRawSeatState(
                            uow.currentConnection(),
                            "no-such-screening",
                            "A-1",
                            "AVAILABLE",
                            null,
                            null,
                            null)))
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(SQLException.class);
  }

  private void seedPrerequisites() {
    uow.executeVoid(
        Tx.REQUIRED,
        () -> {
          Connection conn = uow.currentConnection();
          insertUser(conn, "u-1");
          insertMovie(conn, "m-1");
          insertScreen(conn, "screen-1");
          insertScreening(conn, "sc-1", "m-1", "screen-1");
        });
  }

  private void seedAvailableSeat(String screeningId, String seatId) {
    uow.executeVoid(
        Tx.REQUIRED,
        () ->
            insertRawSeatState(
                uow.currentConnection(), screeningId, seatId, "AVAILABLE", null, null, null));
  }

  private void seedReservation(String reservationId) {
    uow.executeVoid(
        Tx.REQUIRED,
        () -> {
          try (PreparedStatement ps =
              uow.currentConnection()
                  .prepareStatement(
                      """
                      INSERT INTO reservations(
                        reservation_id, user_id, screening_id, status,
                        expires_at, created_at, updated_at, version)
                      VALUES (?,?,?,'HOLD',?,?,?,0)
                      """)) {
            ps.setString(1, reservationId);
            ps.setString(2, "u-1");
            ps.setString(3, "sc-1");
            ps.setLong(4, EXPIRES.toEpochMilli());
            ps.setLong(5, NOW.toEpochMilli());
            ps.setLong(6, NOW.toEpochMilli());
            ps.executeUpdate();
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  private void seedHeldSeat(String screeningId, String seatId, String reservationId) {
    uow.executeVoid(
        Tx.REQUIRED,
        () ->
            insertRawSeatState(
                uow.currentConnection(),
                screeningId,
                seatId,
                "HOLD",
                reservationId,
                EXPIRES.toEpochMilli(),
                null));
  }

  private static void insertRawSeatState(
      Connection conn,
      String screeningId,
      String seatId,
      String status,
      String reservationId,
      Long holdExpiresAt,
      String ticketId) {
    try (PreparedStatement ps =
        conn.prepareStatement(
            """
            INSERT INTO seat_states(
              screening_id, seat_id, status, reservation_id,
              hold_expires_at, ticket_id, price, version, updated_at)
            VALUES (?,?,?,?,?,?,1000,0,?)
            """)) {
      ps.setString(1, screeningId);
      ps.setString(2, seatId);
      ps.setString(3, status);
      ps.setObject(4, reservationId);
      ps.setObject(5, holdExpiresAt);
      ps.setObject(6, ticketId);
      ps.setLong(7, NOW.toEpochMilli());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void insertUser(Connection conn, String userId) {
    try (PreparedStatement ps =
        conn.prepareStatement(
            """
            INSERT INTO users(user_id, email, name, password_hash, role, created_at, updated_at, version)
            VALUES (?,?,?,'hash','USER',?,?,0)
            """)) {
      ps.setString(1, userId);
      ps.setString(2, userId + "@example.com");
      ps.setString(3, "Test User");
      ps.setLong(4, NOW.toEpochMilli());
      ps.setLong(5, NOW.toEpochMilli());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void insertMovie(Connection conn, String movieId) {
    try (PreparedStatement ps =
        conn.prepareStatement(
            """
            INSERT INTO movies(
              movie_id, title, description, duration_minutes,
              is_published, created_at, updated_at, version)
            VALUES (?,?,?,120,1,?,?,0)
            """)) {
      ps.setString(1, movieId);
      ps.setString(2, "Test Movie");
      ps.setString(3, "desc");
      ps.setLong(4, NOW.toEpochMilli());
      ps.setLong(5, NOW.toEpochMilli());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void insertScreen(Connection conn, String screenId) {
    try (PreparedStatement ps =
        conn.prepareStatement(
            """
            INSERT INTO screens(screen_id, name, total_seats, created_at, updated_at)
            VALUES (?,?,100,?,?)
            """)) {
      ps.setString(1, screenId);
      ps.setString(2, "Screen 1");
      ps.setLong(3, NOW.toEpochMilli());
      ps.setLong(4, NOW.toEpochMilli());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void insertScreening(
      Connection conn, String screeningId, String movieId, String screenId) {
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
      long start = NOW.plusSeconds(7200).toEpochMilli();
      long end = NOW.plusSeconds(14280).toEpochMilli();
      long salesStart = NOW.minusSeconds(86400).toEpochMilli();
      long salesEnd = NOW.plusSeconds(3600).toEpochMilli();
      ps.setString(1, screeningId);
      ps.setString(2, movieId);
      ps.setString(3, screenId);
      ps.setLong(4, start);
      ps.setLong(5, end);
      ps.setLong(6, salesStart);
      ps.setLong(7, salesEnd);
      ps.setString(8, "OPEN");
      ps.setInt(9, 0);
      ps.setInt(10, 100);
      ps.setInt(11, 0);
      ps.setInt(12, 0);
      ps.setLong(13, NOW.toEpochMilli());
      ps.setLong(14, NOW.toEpochMilli());
      ps.setLong(15, NOW.toEpochMilli());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
