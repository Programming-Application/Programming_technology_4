package com.theater.reservation.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.reservation.domain.Reservation;
import com.theater.reservation.domain.ReservationRepository;
import com.theater.reservation.domain.ReservationStatus;
import com.theater.shared.error.OptimisticLockException;
import com.theater.shared.kernel.ReservationId;
import com.theater.shared.kernel.ScreeningId;
import com.theater.shared.kernel.UserId;
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

class JdbcReservationRepositoryIT {

  private static final Instant NOW = Instant.parse("2026-05-12T00:00:00Z");
  private static final Instant EXPIRES = NOW.plusSeconds(900);

  private Db.TestDb testDb;
  private JdbcUnitOfWork uow;
  private ReservationRepository repository;

  @BeforeEach
  void setup() {
    testDb = Db.openTempFile();
    uow = new JdbcUnitOfWork(testDb.writable(), testDb.readOnly());
    repository = new JdbcReservationRepository(uow);
    seedPrerequisites();
  }

  @AfterEach
  void teardown() {
    testDb.close();
  }

  @Test
  void save_inserts_new_reservation_and_findById_reads_it_back() {
    Reservation reservation = holdReservation("r-1", "u-1", "sc-1");

    uow.executeVoid(Tx.REQUIRED, () -> repository.save(reservation));

    Reservation found =
        uow.execute(Tx.READ_ONLY, () -> repository.findById(new ReservationId("r-1")))
            .orElseThrow();
    assertThat(found.userId()).isEqualTo(new UserId("u-1"));
    assertThat(found.status()).isEqualTo(ReservationStatus.HOLD);
    assertThat(found.expiresAt()).isEqualTo(EXPIRES);
  }

  @Test
  void findActiveByUser_returns_only_hold_status() {
    Reservation hold = holdReservation("r-2", "u-1", "sc-1");
    Reservation confirmed = confirmedReservation("r-3", "u-1", "sc-1");
    uow.executeVoid(
        Tx.REQUIRED,
        () -> {
          repository.save(hold);
          repository.save(confirmed);
        });

    var active = uow.execute(Tx.READ_ONLY, () -> repository.findActiveByUser(new UserId("u-1")));

    assertThat(active).hasSize(1);
    assertThat(active.get(0).id()).isEqualTo(new ReservationId("r-2"));
  }

  @Test
  void save_update_increments_version_and_updates_status() {
    Reservation reservation = holdReservation("r-4", "u-1", "sc-1");
    uow.executeVoid(Tx.REQUIRED, () -> repository.save(reservation));

    Reservation loaded =
        uow.execute(Tx.READ_ONLY, () -> repository.findById(new ReservationId("r-4")))
            .orElseThrow();
    // ★ B の Repository 規約: 呼出側が version + 1 を載せて save する
    // (Reservation.toCanceled() と同じ流儀)
    Reservation updated =
        new Reservation(
            loaded.id(),
            loaded.userId(),
            loaded.screeningId(),
            ReservationStatus.CONFIRMED,
            null,
            loaded.createdAt(),
            NOW,
            loaded.version() + 1);
    uow.executeVoid(Tx.REQUIRED, () -> repository.save(updated));

    Reservation reloaded =
        uow.execute(Tx.READ_ONLY, () -> repository.findById(new ReservationId("r-4")))
            .orElseThrow();
    assertThat(reloaded.status()).isEqualTo(ReservationStatus.CONFIRMED);
    assertThat(reloaded.version()).isEqualTo(1L);
  }

  @Test
  void save_with_stale_version_throws_optimistic_lock_exception() {
    Reservation reservation = holdReservation("r-5", "u-1", "sc-1");
    uow.executeVoid(Tx.REQUIRED, () -> repository.save(reservation));

    Reservation loaded =
        uow.execute(Tx.READ_ONLY, () -> repository.findById(new ReservationId("r-5")))
            .orElseThrow();
    // 1 回目: version+1 を載せて update → DB version 0 → 1 で成功
    Reservation firstSave =
        new Reservation(
            loaded.id(),
            loaded.userId(),
            loaded.screeningId(),
            ReservationStatus.CONFIRMED,
            null,
            loaded.createdAt(),
            NOW,
            loaded.version() + 1);
    uow.executeVoid(Tx.REQUIRED, () -> repository.save(firstSave));

    // 2 回目: 同じ "stale" (= 古いまま再利用) を save → DB は既に version=1 なので失敗
    assertThatThrownBy(() -> uow.executeVoid(Tx.REQUIRED, () -> repository.save(firstSave)))
        .isInstanceOf(OptimisticLockException.class);
  }

  @Test
  void fk_nonexistent_user_throws() {
    Reservation reservation = holdReservation("r-6", "no-such-user", "sc-1");
    assertThatThrownBy(() -> uow.executeVoid(Tx.REQUIRED, () -> repository.save(reservation)))
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(SQLException.class);
  }

  @Test
  void fk_nonexistent_screening_throws() {
    Reservation reservation = holdReservation("r-7", "u-1", "no-such-screening");
    assertThatThrownBy(() -> uow.executeVoid(Tx.REQUIRED, () -> repository.save(reservation)))
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(SQLException.class);
  }

  @Test
  void check_hold_without_expires_at_throws() {
    assertThatThrownBy(
            () ->
                uow.executeVoid(
                    Tx.REQUIRED,
                    () ->
                        insertRawReservation(
                            uow.currentConnection(), "r-8", "u-1", "sc-1", "HOLD", null)))
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(SQLException.class);
  }

  private void seedPrerequisites() {
    uow.executeVoid(
        Tx.REQUIRED,
        () -> {
          Connection conn = uow.currentConnection();
          insertUser(conn, "u-1", "test@example.com", "Test User");
          insertMovie(conn, "m-1");
          insertScreen(conn, "screen-1");
          insertScreening(conn, "sc-1", "m-1", "screen-1");
        });
  }

  private Reservation holdReservation(String id, String userId, String screeningId) {
    return new Reservation(
        new ReservationId(id),
        new UserId(userId),
        new ScreeningId(screeningId),
        ReservationStatus.HOLD,
        EXPIRES,
        NOW,
        NOW,
        0L);
  }

  private Reservation confirmedReservation(String id, String userId, String screeningId) {
    return new Reservation(
        new ReservationId(id),
        new UserId(userId),
        new ScreeningId(screeningId),
        ReservationStatus.CONFIRMED,
        null,
        NOW,
        NOW,
        0L);
  }

  private static void insertUser(Connection conn, String userId, String email, String name) {
    try (PreparedStatement ps =
        conn.prepareStatement(
            """
            INSERT INTO users(user_id, email, name, password_hash, role, created_at, updated_at, version)
            VALUES (?,?,?,'hash','USER',?,?,0)
            """)) {
      ps.setString(1, userId);
      ps.setString(2, email);
      ps.setString(3, name);
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

  private static void insertRawReservation(
      Connection conn,
      String id,
      String userId,
      String screeningId,
      String status,
      Long expiresAt) {
    try (PreparedStatement ps =
        conn.prepareStatement(
            """
            INSERT INTO reservations(
              reservation_id, user_id, screening_id, status,
              expires_at, created_at, updated_at, version)
            VALUES (?,?,?,?,?,?,?,0)
            """)) {
      ps.setString(1, id);
      ps.setString(2, userId);
      ps.setString(3, screeningId);
      ps.setString(4, status);
      ps.setObject(5, expiresAt);
      ps.setLong(6, NOW.toEpochMilli());
      ps.setLong(7, NOW.toEpochMilli());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
