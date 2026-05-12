package com.theater.shared.bootstrap;

import com.theater.shared.kernel.Clock;
import com.theater.shared.tx.Tx;
import com.theater.shared.tx.UnitOfWork;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Loads development demo data once for an empty application database. */
public final class DemoDataLoader {

  private static final String USER_PASSWORD_HASH =
      "$2a$12$32X3HXgeFtASt8gNgPWV7ui3FsRCm4UYgRPWoamI/s9dwPzp9uRMu";
  private static final int STANDARD_PRICE = 1_500;

  private final UnitOfWork uow;
  private final Clock clock;

  public DemoDataLoader(UnitOfWork uow, Clock clock) {
    this.uow = Objects.requireNonNull(uow, "uow");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public void loadIfEmpty() {
    if (alreadyLoaded()) {
      return;
    }
    uow.executeVoid(Tx.REQUIRED, this::insertAll);
  }

  private boolean alreadyLoaded() {
    return uow.execute(Tx.READ_ONLY, () -> countRows("movies") > 0);
  }

  private void insertAll() {
    Instant now = clock.now();
    Connection conn = uow.currentConnection();
    insertUsers(conn, now);
    insertMovies(conn, now);
    insertScreen(conn, now);
    insertSeats(conn);
    insertScreenings(conn, now);
    insertSeatStates(conn, now);
  }

  private long countRows(String table) {
    try (Statement stmt = uow.currentConnection().createStatement();
        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
      rs.next();
      return rs.getLong(1);
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to count table: " + table, e);
    }
  }

  private static void insertUsers(Connection conn, Instant now) {
    insertUser(conn, "demo-user", "alice@example.com", "Alice Demo", "USER", now);
    insertUser(conn, "demo-admin", "admin@example.com", "Admin Demo", "ADMIN", now);
  }

  private static void insertUser(
      Connection conn, String id, String email, String name, String role, Instant now) {
    try (PreparedStatement ps =
        conn.prepareStatement(
            """
            INSERT OR IGNORE INTO users(
              user_id, email, name, password_hash, role, created_at, updated_at, version)
            VALUES (?,?,?,?,?,?,?,0)
            """)) {
      ps.setString(1, id);
      ps.setString(2, email);
      ps.setString(3, name);
      ps.setString(4, USER_PASSWORD_HASH);
      ps.setString(5, role);
      ps.setLong(6, toMillis(now));
      ps.setLong(7, toMillis(now));
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to insert demo user: " + id, e);
    }
  }

  private static void insertMovies(Connection conn, Instant now) {
    insertMovie(
        conn, "demo-movie-1", "River Line", "Quiet suspense along the old tracks.", 118, now);
    insertMovie(
        conn, "demo-movie-2", "Midnight Arcade", "A neon mystery inside a closed mall.", 104, now);
    insertMovie(
        conn,
        "demo-movie-3",
        "Cloud Harbor",
        "A gentle drama about rebuilding a seaside town.",
        126,
        now);
  }

  private static void insertMovie(
      Connection conn,
      String id,
      String title,
      String description,
      int durationMinutes,
      Instant now) {
    try (PreparedStatement ps =
        conn.prepareStatement(
            """
            INSERT OR IGNORE INTO movies(
              movie_id, title, description, duration_minutes, is_published,
              created_at, updated_at, version)
            VALUES (?,?,?,?,1,?,?,0)
            """)) {
      ps.setString(1, id);
      ps.setString(2, title);
      ps.setString(3, description);
      ps.setInt(4, durationMinutes);
      ps.setLong(5, toMillis(now));
      ps.setLong(6, toMillis(now));
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to insert demo movie: " + id, e);
    }
  }

  private static void insertScreen(Connection conn, Instant now) {
    try (PreparedStatement ps =
        conn.prepareStatement(
            """
            INSERT OR IGNORE INTO screens(screen_id, name, total_seats, created_at, updated_at)
            VALUES (?,?,?,?,?)
            """)) {
      ps.setString(1, "demo-screen-1");
      ps.setString(2, "Screen 1");
      ps.setInt(3, 50);
      ps.setLong(4, toMillis(now));
      ps.setLong(5, toMillis(now));
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to insert demo screen", e);
    }
  }

  private static void insertSeats(Connection conn) {
    List<String> rows = List.of("A", "B", "C", "D", "E");
    for (String row : rows) {
      for (int number = 1; number <= 10; number++) {
        insertSeat(conn, row, number);
      }
    }
  }

  private static void insertSeat(Connection conn, String row, int number) {
    try (PreparedStatement ps =
        conn.prepareStatement(
            """
            INSERT OR IGNORE INTO seats(screen_id, seat_id, row, number, seat_type, is_active)
            VALUES (?,?,?,?,?,1)
            """)) {
      ps.setString(1, "demo-screen-1");
      ps.setString(2, row + number);
      ps.setString(3, row);
      ps.setInt(4, number);
      ps.setString(5, number > 8 ? "PREMIUM" : "NORMAL");
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to insert demo seat: " + row + number, e);
    }
  }

  private static void insertScreenings(Connection conn, Instant now) {
    insertScreening(
        conn, "demo-screening-1", "demo-movie-1", now.plusSeconds(3 * 60 * 60), 118, now);
    insertScreening(
        conn, "demo-screening-2", "demo-movie-2", now.plusSeconds(2 * 24 * 60 * 60), 104, now);
    insertScreening(
        conn, "demo-screening-3", "demo-movie-3", now.plusSeconds(5 * 24 * 60 * 60), 126, now);
  }

  private static void insertScreening(
      Connection conn, String id, String movieId, Instant start, int durationMinutes, Instant now) {
    try (PreparedStatement ps =
        conn.prepareStatement(
            """
            INSERT OR IGNORE INTO screenings(
              screening_id, movie_id, screen_id, start_time, end_time,
              sales_start_at, sales_end_at, status, is_private,
              available_seat_count, reserved_seat_count, sold_seat_count,
              last_updated, created_at, updated_at, version)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)
            """)) {
      ps.setString(1, id);
      ps.setString(2, movieId);
      ps.setString(3, "demo-screen-1");
      ps.setLong(4, toMillis(start));
      ps.setLong(5, toMillis(start.plusSeconds(durationMinutes * 60L)));
      ps.setLong(6, toMillis(now.minusSeconds(24 * 60 * 60)));
      ps.setLong(7, toMillis(start.minusSeconds(30 * 60)));
      ps.setString(8, "OPEN");
      ps.setInt(9, 0);
      ps.setInt(10, 50);
      ps.setInt(11, 0);
      ps.setInt(12, 0);
      ps.setLong(13, toMillis(now));
      ps.setLong(14, toMillis(now));
      ps.setLong(15, toMillis(now));
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to insert demo screening: " + id, e);
    }
  }

  private static void insertSeatStates(Connection conn, Instant now) {
    List<String> screenings = List.of("demo-screening-1", "demo-screening-2", "demo-screening-3");
    List<String> rows = List.of("A", "B", "C", "D", "E");
    for (String screening : screenings) {
      for (String row : rows) {
        for (int number = 1; number <= 10; number++) {
          insertSeatState(conn, screening, row + number, now);
        }
      }
    }
  }

  private static void insertSeatState(
      Connection conn, String screeningId, String seatId, Instant now) {
    try (PreparedStatement ps =
        conn.prepareStatement(
            """
            INSERT OR IGNORE INTO seat_states(
              screening_id, seat_id, status, reservation_id, hold_expires_at,
              ticket_id, price, version, updated_at)
            VALUES (?,?,'AVAILABLE',NULL,NULL,NULL,?,0,?)
            """)) {
      ps.setString(1, screeningId);
      ps.setString(2, seatId);
      ps.setInt(3, STANDARD_PRICE);
      ps.setLong(4, toMillis(now));
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(
          "Failed to insert demo seat state: " + screeningId + "/" + seatId, e);
    }
  }

  private static long toMillis(Instant instant) {
    return instant.toEpochMilli();
  }
}
