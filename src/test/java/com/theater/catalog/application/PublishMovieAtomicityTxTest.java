package com.theater.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.catalog.domain.CatalogQueryRepository;
import com.theater.catalog.domain.Movie;
import com.theater.catalog.domain.MovieRepository;
import com.theater.catalog.infrastructure.CatalogModule;
import com.theater.shared.di.Container;
import com.theater.shared.kernel.Clock;
import com.theater.shared.tx.JdbcUnitOfWork;
import com.theater.shared.tx.Tx;
import com.theater.shared.tx.UnitOfWork;
import com.theater.testkit.Db;
import com.theater.testkit.FixedClock;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class PublishMovieAtomicityTxTest {

  private static final Instant CREATED_AT = Instant.parse("2026-05-04T00:00:00Z");
  private static final Instant PUBLISHED_AT = Instant.parse("2026-05-05T00:00:00Z");

  private Db.TestDb testDb;
  private JdbcUnitOfWork uow;
  private CatalogQueryRepository queryRepository;
  private MovieRepository movieRepository;

  @BeforeEach
  void setup() {
    testDb = Db.openTempFile();
    uow = new JdbcUnitOfWork(testDb.writable(), testDb.readOnly());
    var container = new Container();
    container.registerSingleton(UnitOfWork.class, c -> uow);
    container.registerSingleton(Clock.class, c -> FixedClock.at(PUBLISHED_AT));
    container.install(new CatalogModule());
    queryRepository = container.resolve(CatalogQueryRepository.class);
    movieRepository = container.resolve(MovieRepository.class);
    seedUnpublishedMovie();
  }

  @AfterEach
  void teardown() {
    testDb.close();
  }

  @Test
  void exception_after_movie_save_rolls_back_publish_update() {
    var useCase =
        new PublishMovieUseCase(
            uow,
            queryRepository,
            new ThrowAfterSaveMovieRepository(movieRepository),
            FixedClock.at(PUBLISHED_AT));

    assertThatThrownBy(() -> useCase.execute(new PublishMovieUseCase.Command("movie-hidden")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("injected failure after movie save");

    assertThat(movieColumnInt("is_published")).isZero();
    assertThat(movieColumnInt("version")).isZero();
    assertThat(movieColumnLong("updated_at")).isEqualTo(CREATED_AT.toEpochMilli());
  }

  private void seedUnpublishedMovie() {
    uow.executeVoid(
        Tx.REQUIRED,
        () -> {
          try (PreparedStatement ps =
              uow.currentConnection()
                  .prepareStatement(
                      """
                      INSERT INTO movies(
                        movie_id, title, description, duration_minutes,
                        is_published, created_at, updated_at, version)
                      VALUES (?,?,?,?,?,?,?,0)
                      """)) {
            ps.setString(1, "movie-hidden");
            ps.setString(2, "Hidden Cut");
            ps.setString(3, "Internal only.");
            ps.setInt(4, 90);
            ps.setInt(5, 0);
            ps.setLong(6, CREATED_AT.toEpochMilli());
            ps.setLong(7, CREATED_AT.toEpochMilli());
            ps.executeUpdate();
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  private int movieColumnInt(String column) {
    return Math.toIntExact(movieColumnLong(column));
  }

  private long movieColumnLong(String column) {
    return uow.execute(
        Tx.READ_ONLY,
        () -> {
          try (PreparedStatement ps =
              uow.currentConnection()
                  .prepareStatement("SELECT " + column + " FROM movies WHERE movie_id=?")) {
            ps.setString(1, "movie-hidden");
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              return rs.getLong(1);
            }
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  private static final class ThrowAfterSaveMovieRepository implements MovieRepository {

    private final MovieRepository delegate;

    ThrowAfterSaveMovieRepository(MovieRepository delegate) {
      this.delegate = delegate;
    }

    @Override
    public void save(Movie movie) {
      delegate.save(movie);
      throw new IllegalStateException("injected failure after movie save");
    }
  }
}
