package com.theater.catalog.infrastructure;

import com.theater.catalog.domain.CatalogQueryRepository;
import com.theater.catalog.domain.Movie;
import com.theater.catalog.domain.Screen;
import com.theater.catalog.domain.Screening;
import com.theater.catalog.domain.ScreeningRepository;
import com.theater.catalog.domain.ScreeningStatus;
import com.theater.shared.error.OptimisticLockException;
import com.theater.shared.kernel.MovieId;
import com.theater.shared.kernel.ScreenId;
import com.theater.shared.kernel.ScreeningId;
import com.theater.shared.tx.UnitOfWork;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** JDBC implementation for catalog repositories. */
final class JdbcCatalogRepository implements CatalogQueryRepository, ScreeningRepository {

  private final UnitOfWork uow;

  JdbcCatalogRepository(UnitOfWork uow) {
    this.uow = Objects.requireNonNull(uow, "uow");
  }

  @Override
  public List<Movie> findPublishedMovies() {
    return queryMovies(
        """
        SELECT movie_id, title, description, duration_minutes, is_published,
               created_at, updated_at, version
          FROM movies
         WHERE is_published = 1
         ORDER BY title
        """);
  }

  @Override
  public List<Movie> searchMoviesByTitle(String titlePart) {
    try (PreparedStatement ps =
        connection()
            .prepareStatement(
                """
                SELECT movie_id, title, description, duration_minutes, is_published,
                       created_at, updated_at, version
                  FROM movies
                 WHERE is_published = 1
                   AND lower(title) LIKE lower(?)
                 ORDER BY title
                """)) {
      ps.setString(1, "%" + titlePart + "%");
      return readMovies(ps);
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to search movies", e);
    }
  }

  @Override
  public Optional<Movie> findMovieById(MovieId id) {
    try (PreparedStatement ps =
        connection()
            .prepareStatement(
                """
                SELECT movie_id, title, description, duration_minutes, is_published,
                       created_at, updated_at, version
                  FROM movies
                 WHERE movie_id = ?
                """)) {
      ps.setString(1, id.value());
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(toMovie(rs)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to find movie: " + id.value(), e);
    }
  }

  @Override
  public List<ScreeningWithMovie> findUpcomingScreenings(Instant from, Instant to) {
    try (PreparedStatement ps = connection().prepareStatement(upcomingScreeningSql())) {
      ps.setLong(1, toMillis(from));
      ps.setLong(2, toMillis(from));
      ps.setLong(3, toMillis(to));
      return readScreeningWithMovies(ps);
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to find upcoming screenings", e);
    }
  }

  @Override
  public List<ScreeningWithMovie> findScreeningsByMovie(MovieId movieId, Instant from) {
    try (PreparedStatement ps =
        connection()
            .prepareStatement(
                """
                SELECT s.screening_id, s.movie_id, s.screen_id, s.start_time, s.end_time,
                       s.sales_start_at, s.sales_end_at, s.status, s.is_private,
                       s.available_seat_count, s.reserved_seat_count, s.sold_seat_count,
                       s.last_updated, s.created_at, s.updated_at, s.version,
                       m.title AS movie_title, sc.name AS screen_name, m.duration_minutes
                  FROM screenings s
                  JOIN movies m ON m.movie_id = s.movie_id
                  JOIN screens sc ON sc.screen_id = s.screen_id
                 WHERE s.movie_id = ?
                   AND s.status = 'OPEN'
                   AND s.sales_end_at > ?
                   AND s.start_time >= ?
                 ORDER BY s.start_time
                """)) {
      long now = toMillis(from);
      ps.setString(1, movieId.value());
      ps.setLong(2, now);
      ps.setLong(3, now);
      return readScreeningWithMovies(ps);
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to find screenings by movie: " + movieId.value(), e);
    }
  }

  @Override
  public Optional<ScreeningDetail> findScreeningDetail(ScreeningId id) {
    try (PreparedStatement ps =
        connection()
            .prepareStatement(
                """
                SELECT s.screening_id, s.movie_id, s.screen_id, s.start_time, s.end_time,
                       s.sales_start_at, s.sales_end_at, s.status, s.is_private,
                       s.available_seat_count, s.reserved_seat_count, s.sold_seat_count,
                       s.last_updated, s.created_at, s.updated_at, s.version,
                       m.title, m.description, m.duration_minutes, m.is_published,
                       m.created_at AS movie_created_at, m.updated_at AS movie_updated_at,
                       m.version AS movie_version,
                       sc.name, sc.total_seats, sc.created_at AS screen_created_at,
                       sc.updated_at AS screen_updated_at
                  FROM screenings s
                  JOIN movies m ON m.movie_id = s.movie_id
                  JOIN screens sc ON sc.screen_id = s.screen_id
                 WHERE s.screening_id = ?
                """)) {
      ps.setString(1, id.value());
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new ScreeningDetail(toScreening(rs), toJoinedMovie(rs), toJoinedScreen(rs)));
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to find screening detail: " + id.value(), e);
    }
  }

  @Override
  public Optional<Screening> findById(ScreeningId id) {
    try (PreparedStatement ps =
        connection()
            .prepareStatement(
                """
                SELECT screening_id, movie_id, screen_id, start_time, end_time,
                       sales_start_at, sales_end_at, status, is_private,
                       available_seat_count, reserved_seat_count, sold_seat_count,
                       last_updated, created_at, updated_at, version
                  FROM screenings
                 WHERE screening_id = ?
                """)) {
      ps.setString(1, id.value());
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(toScreening(rs)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to find screening: " + id.value(), e);
    }
  }

  @Override
  public List<Screening> findUpcoming(Instant from, Instant to) {
    try (PreparedStatement ps =
        connection()
            .prepareStatement(
                """
                SELECT screening_id, movie_id, screen_id, start_time, end_time,
                       sales_start_at, sales_end_at, status, is_private,
                       available_seat_count, reserved_seat_count, sold_seat_count,
                       last_updated, created_at, updated_at, version
                  FROM screenings
                 WHERE status = 'OPEN'
                   AND sales_end_at > ?
                   AND start_time >= ?
                   AND start_time < ?
                 ORDER BY start_time
                """)) {
      ps.setLong(1, toMillis(from));
      ps.setLong(2, toMillis(from));
      ps.setLong(3, toMillis(to));
      List<Screening> screenings = new ArrayList<>();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          screenings.add(toScreening(rs));
        }
      }
      return screenings;
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to find upcoming screening aggregates", e);
    }
  }

  @Override
  public void save(Screening screening) {
    Objects.requireNonNull(screening, "screening");
    if (findById(screening.id()).isPresent()) {
      update(screening);
    } else {
      insert(screening);
    }
  }

  private void insert(Screening screening) {
    try (PreparedStatement ps =
        connection()
            .prepareStatement(
                """
                INSERT INTO screenings(
                  screening_id, movie_id, screen_id, start_time, end_time,
                  sales_start_at, sales_end_at, status, is_private,
                  available_seat_count, reserved_seat_count, sold_seat_count,
                  last_updated, created_at, updated_at, version)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
      bindScreening(ps, screening);
      ps.setLong(16, screening.version());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to insert screening: " + screening.id().value(), e);
    }
  }

  private void update(Screening screening) {
    try (PreparedStatement ps =
        connection()
            .prepareStatement(
                """
                UPDATE screenings
                   SET movie_id = ?,
                       screen_id = ?,
                       start_time = ?,
                       end_time = ?,
                       sales_start_at = ?,
                       sales_end_at = ?,
                       status = ?,
                       is_private = ?,
                       available_seat_count = ?,
                       reserved_seat_count = ?,
                       sold_seat_count = ?,
                       last_updated = ?,
                       updated_at = ?,
                       version = version + 1
                 WHERE screening_id = ?
                   AND version = ?
                """)) {
      ps.setString(1, screening.movieId().value());
      ps.setString(2, screening.screenId().value());
      ps.setLong(3, toMillis(screening.startTime()));
      ps.setLong(4, toMillis(screening.endTime()));
      ps.setLong(5, toMillis(screening.salesStartAt()));
      ps.setLong(6, toMillis(screening.salesEndAt()));
      ps.setString(7, screening.status().name());
      ps.setInt(8, toInt(screening.privateScreening()));
      ps.setInt(9, screening.availableSeatCount());
      ps.setInt(10, screening.reservedSeatCount());
      ps.setInt(11, screening.soldSeatCount());
      ps.setLong(12, toMillis(screening.lastUpdated()));
      ps.setLong(13, toMillis(screening.updatedAt()));
      ps.setString(14, screening.id().value());
      ps.setLong(15, screening.version());
      int updated = ps.executeUpdate();
      if (updated != 1) {
        throw new OptimisticLockException("Screening", screening.id().value());
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to update screening: " + screening.id().value(), e);
    }
  }

  private List<Movie> queryMovies(String sql) {
    try (Statement stmt = connection().createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      List<Movie> movies = new ArrayList<>();
      while (rs.next()) {
        movies.add(toMovie(rs));
      }
      return movies;
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to query movies", e);
    }
  }

  private static List<Movie> readMovies(PreparedStatement ps) throws SQLException {
    List<Movie> movies = new ArrayList<>();
    try (ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        movies.add(toMovie(rs));
      }
    }
    return movies;
  }

  private static String upcomingScreeningSql() {
    return """
        SELECT s.screening_id, s.movie_id, s.screen_id, s.start_time, s.end_time,
               s.sales_start_at, s.sales_end_at, s.status, s.is_private,
               s.available_seat_count, s.reserved_seat_count, s.sold_seat_count,
               s.last_updated, s.created_at, s.updated_at, s.version,
               m.title AS movie_title, sc.name AS screen_name, m.duration_minutes
          FROM screenings s
          JOIN movies m ON m.movie_id = s.movie_id
          JOIN screens sc ON sc.screen_id = s.screen_id
         WHERE s.status = 'OPEN'
           AND s.sales_end_at > ?
           AND s.start_time >= ?
           AND s.start_time < ?
         ORDER BY s.start_time
        """;
  }

  private static List<ScreeningWithMovie> readScreeningWithMovies(PreparedStatement ps)
      throws SQLException {
    List<ScreeningWithMovie> screenings = new ArrayList<>();
    try (ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        screenings.add(
            new ScreeningWithMovie(
                toScreening(rs),
                rs.getString("movie_title"),
                rs.getString("screen_name"),
                rs.getInt("duration_minutes")));
      }
    }
    return screenings;
  }

  private static Movie toMovie(ResultSet rs) throws SQLException {
    return new Movie(
        new MovieId(rs.getString("movie_id")),
        rs.getString("title"),
        rs.getString("description"),
        rs.getInt("duration_minutes"),
        rs.getInt("is_published") == 1,
        toInstant(rs.getLong("created_at")),
        toInstant(rs.getLong("updated_at")),
        rs.getLong("version"));
  }

  private static Movie toJoinedMovie(ResultSet rs) throws SQLException {
    return new Movie(
        new MovieId(rs.getString("movie_id")),
        rs.getString("title"),
        rs.getString("description"),
        rs.getInt("duration_minutes"),
        rs.getInt("is_published") == 1,
        toInstant(rs.getLong("movie_created_at")),
        toInstant(rs.getLong("movie_updated_at")),
        rs.getLong("movie_version"));
  }

  private static Screen toJoinedScreen(ResultSet rs) throws SQLException {
    return new Screen(
        new ScreenId(rs.getString("screen_id")),
        rs.getString("name"),
        rs.getInt("total_seats"),
        toInstant(rs.getLong("screen_created_at")),
        toInstant(rs.getLong("screen_updated_at")));
  }

  private static Screening toScreening(ResultSet rs) throws SQLException {
    return new Screening(
        new ScreeningId(rs.getString("screening_id")),
        new MovieId(rs.getString("movie_id")),
        new ScreenId(rs.getString("screen_id")),
        toInstant(rs.getLong("start_time")),
        toInstant(rs.getLong("end_time")),
        toInstant(rs.getLong("sales_start_at")),
        toInstant(rs.getLong("sales_end_at")),
        ScreeningStatus.valueOf(rs.getString("status")),
        rs.getInt("is_private") == 1,
        rs.getInt("available_seat_count"),
        rs.getInt("reserved_seat_count"),
        rs.getInt("sold_seat_count"),
        toInstant(rs.getLong("last_updated")),
        toInstant(rs.getLong("created_at")),
        toInstant(rs.getLong("updated_at")),
        rs.getLong("version"));
  }

  private static void bindScreening(PreparedStatement ps, Screening screening) throws SQLException {
    ps.setString(1, screening.id().value());
    ps.setString(2, screening.movieId().value());
    ps.setString(3, screening.screenId().value());
    ps.setLong(4, toMillis(screening.startTime()));
    ps.setLong(5, toMillis(screening.endTime()));
    ps.setLong(6, toMillis(screening.salesStartAt()));
    ps.setLong(7, toMillis(screening.salesEndAt()));
    ps.setString(8, screening.status().name());
    ps.setInt(9, toInt(screening.privateScreening()));
    ps.setInt(10, screening.availableSeatCount());
    ps.setInt(11, screening.reservedSeatCount());
    ps.setInt(12, screening.soldSeatCount());
    ps.setLong(13, toMillis(screening.lastUpdated()));
    ps.setLong(14, toMillis(screening.createdAt()));
    ps.setLong(15, toMillis(screening.updatedAt()));
  }

  private Connection connection() {
    return uow.currentConnection();
  }

  private static long toMillis(Instant instant) {
    return instant.toEpochMilli();
  }

  private static Instant toInstant(long millis) {
    return Instant.ofEpochMilli(millis);
  }

  private static int toInt(boolean value) {
    return value ? 1 : 0;
  }
}
