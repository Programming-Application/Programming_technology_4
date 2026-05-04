package com.theater.catalog.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.catalog.domain.MovieId;
import com.theater.catalog.domain.ScreenId;
import com.theater.catalog.domain.Screening;
import com.theater.catalog.domain.ScreeningId;
import com.theater.catalog.domain.ScreeningStatus;
import com.theater.shared.error.OptimisticLockException;
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

class JdbcCatalogRepositoryIT {

  private static final Instant NOW = Instant.parse("2026-05-04T00:00:00Z");

  private Db.TestDb testDb;
  private JdbcUnitOfWork uow;
  private JdbcCatalogRepository repository;

  @BeforeEach
  void setup() {
    testDb = Db.openTempFile();
    uow = new JdbcUnitOfWork(testDb.writable(), testDb.readOnly());
    repository = new JdbcCatalogRepository(uow);
    seedCatalog();
  }

  @AfterEach
  void teardown() {
    testDb.close();
  }

  @Test
  void findPublishedMovies_returns_only_published_movies_ordered_by_title() {
    var movies = uow.execute(Tx.READ_ONLY, repository::findPublishedMovies);

    assertThat(movies).extracting(movie -> movie.id().value()).containsExactly("movie-1");
  }

  @Test
  void searchMoviesByTitle_searches_published_movies_case_insensitively() {
    var movies = uow.execute(Tx.READ_ONLY, () -> repository.searchMoviesByTitle("river"));

    assertThat(movies).extracting(movie -> movie.title()).containsExactly("River Line");
  }

  @Test
  void findUpcomingScreenings_returns_open_sales_and_time_window_only() {
    var screenings =
        uow.execute(
            Tx.READ_ONLY, () -> repository.findUpcomingScreenings(NOW, NOW.plusSeconds(86_400)));

    assertThat(screenings)
        .extracting(row -> row.screening().id().value())
        .containsExactly("screening-open");
  }

  @Test
  void findScreeningDetail_returns_movie_screen_and_counters() {
    var detail =
        uow.execute(
                Tx.READ_ONLY,
                () -> repository.findScreeningDetail(new ScreeningId("screening-open")))
            .orElseThrow();

    assertThat(detail.movie().title()).isEqualTo("River Line");
    assertThat(detail.screen().name()).isEqualTo("Screen 1");
    assertThat(detail.screening().availableSeatCount()).isEqualTo(96);
  }

  @Test
  void save_inserts_new_screening_and_findById_reads_it_back() {
    var screening =
        new Screening(
            new ScreeningId("screening-new"),
            new MovieId("movie-1"),
            new ScreenId("screen-1"),
            NOW.plusSeconds(90_000),
            NOW.plusSeconds(97_080),
            NOW.minusSeconds(3_600),
            NOW.plusSeconds(86_000),
            ScreeningStatus.OPEN,
            false,
            100,
            0,
            0,
            NOW,
            NOW,
            NOW,
            0);

    uow.executeVoid(Tx.REQUIRED, () -> repository.save(screening));

    var found =
        uow.execute(Tx.READ_ONLY, () -> repository.findById(new ScreeningId("screening-new")))
            .orElseThrow();
    assertThat(found.movieId()).isEqualTo(new MovieId("movie-1"));
  }

  @Test
  void save_when_version_is_stale_throws_optimistic_lock_exception() {
    var stale =
        uow.execute(Tx.READ_ONLY, () -> repository.findById(new ScreeningId("screening-open")))
            .orElseThrow();
    uow.executeVoid(Tx.REQUIRED, () -> repository.save(stale));

    assertThatThrownBy(() -> uow.executeVoid(Tx.REQUIRED, () -> repository.save(stale)))
        .isInstanceOf(OptimisticLockException.class);
  }

  @Test
  void migration_constraints_reject_invalid_catalog_rows() {
    assertThatThrownBy(
            () ->
                uow.executeVoid(
                    Tx.REQUIRED,
                    () ->
                        insertMovie(uow.currentConnection(), "bad-movie", "Invalid", "", -1, true)))
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(SQLException.class);
  }

  private void seedCatalog() {
    uow.executeVoid(
        Tx.REQUIRED,
        () -> {
          var conn = uow.currentConnection();
          insertMovie(conn, "movie-1", "River Line", "Quiet suspense.", 118, true);
          insertMovie(conn, "movie-hidden", "Hidden Cut", "Internal only.", 90, false);
          insertScreen(conn);
          insertScreening(conn, "screening-open", ScreeningStatus.OPEN, NOW.plusSeconds(3_600), 96);
          insertScreening(
              conn, "screening-closed", ScreeningStatus.CLOSED, NOW.plusSeconds(5_400), 100);
          insertScreening(
              conn, "screening-late", ScreeningStatus.OPEN, NOW.plusSeconds(200_000), 100);
        });
  }

  private static void insertMovie(
      Connection conn,
      String id,
      String title,
      String description,
      int durationMinutes,
      boolean published) {
    try (PreparedStatement ps =
        conn.prepareStatement(
            """
            INSERT INTO movies(movie_id, title, description, duration_minutes,
                               is_published, created_at, updated_at, version)
            VALUES (?,?,?,?,?,?,?,0)
            """)) {
      ps.setString(1, id);
      ps.setString(2, title);
      ps.setString(3, description);
      ps.setInt(4, durationMinutes);
      ps.setInt(5, published ? 1 : 0);
      ps.setLong(6, NOW.toEpochMilli());
      ps.setLong(7, NOW.toEpochMilli());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void insertScreen(Connection conn) {
    try (PreparedStatement ps =
        conn.prepareStatement(
            """
            INSERT INTO screens(screen_id, name, total_seats, created_at, updated_at)
            VALUES (?,?,?,?,?)
            """)) {
      ps.setString(1, "screen-1");
      ps.setString(2, "Screen 1");
      ps.setInt(3, 100);
      ps.setLong(4, NOW.toEpochMilli());
      ps.setLong(5, NOW.toEpochMilli());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void insertScreening(
      Connection conn, String id, ScreeningStatus status, Instant startTime, int availableSeats) {
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
      ps.setString(1, id);
      ps.setString(2, "movie-1");
      ps.setString(3, "screen-1");
      ps.setLong(4, startTime.toEpochMilli());
      ps.setLong(5, startTime.plusSeconds(7_080).toEpochMilli());
      ps.setLong(6, NOW.minusSeconds(86_400).toEpochMilli());
      ps.setLong(7, startTime.minusSeconds(1_800).toEpochMilli());
      ps.setString(8, status.name());
      ps.setInt(9, 0);
      ps.setInt(10, availableSeats);
      ps.setInt(11, 100 - availableSeats);
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
