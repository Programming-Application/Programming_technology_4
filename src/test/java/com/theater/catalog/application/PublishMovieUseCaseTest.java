package com.theater.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.catalog.domain.CatalogQueryRepository;
import com.theater.catalog.domain.CatalogQueryRepository.ScreeningDetail;
import com.theater.catalog.domain.CatalogQueryRepository.ScreeningWithMovie;
import com.theater.catalog.domain.Movie;
import com.theater.catalog.domain.MovieRepository;
import com.theater.shared.error.NotFoundException;
import com.theater.shared.kernel.MovieId;
import com.theater.shared.kernel.ScreeningId;
import com.theater.shared.tx.Tx;
import com.theater.shared.tx.UnitOfWork;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

final class PublishMovieUseCaseTest {

  private static final Instant CREATED_AT = Instant.parse("2026-05-04T00:00:00Z");
  private static final Instant PUBLISHED_AT = Instant.parse("2026-05-05T00:00:00Z");

  private final FakeUnitOfWork uow = new FakeUnitOfWork();
  private final FakeCatalogRepository queryRepository = new FakeCatalogRepository();
  private final FakeMovieRepository movieRepository = new FakeMovieRepository();
  private final PublishMovieUseCase useCase =
      new PublishMovieUseCase(uow, queryRepository, movieRepository, () -> PUBLISHED_AT);

  @Test
  void unpublished_movie_is_saved_as_published_in_required_tx() {
    queryRepository.movie = Optional.of(movie(false));

    useCase.execute(new PublishMovieUseCase.Command("movie-1"));

    assertThat(uow.lastMode()).isEqualTo(Tx.REQUIRED);
    assertThat(movieRepository.saved).isNotNull();
    assertThat(movieRepository.saved.published()).isTrue();
    assertThat(movieRepository.saved.updatedAt()).isEqualTo(PUBLISHED_AT);
    assertThat(movieRepository.saved.version()).isEqualTo(3);
  }

  @Test
  void already_published_movie_is_idempotent() {
    queryRepository.movie = Optional.of(movie(true));

    useCase.execute(new PublishMovieUseCase.Command("movie-1"));

    assertThat(movieRepository.saved).isNull();
  }

  @Test
  void missing_movie_throws_not_found() {
    queryRepository.movie = Optional.empty();

    assertThatThrownBy(() -> useCase.execute(new PublishMovieUseCase.Command("missing")))
        .isInstanceOf(NotFoundException.class)
        .hasMessageContaining("Movie");
    assertThat(movieRepository.saved).isNull();
  }

  private static Movie movie(boolean published) {
    return new Movie(
        new MovieId("movie-1"),
        "River Line",
        "Quiet suspense.",
        118,
        published,
        CREATED_AT,
        CREATED_AT,
        3);
  }

  private static final class FakeUnitOfWork implements UnitOfWork {
    private Tx lastMode;

    @Override
    public <R> R execute(Tx mode, Supplier<R> work) {
      lastMode = mode;
      return work.get();
    }

    @Override
    public Connection currentConnection() {
      throw new UnsupportedOperationException("test fake does not expose a JDBC connection");
    }

    Tx lastMode() {
      return lastMode;
    }
  }

  private static final class FakeMovieRepository implements MovieRepository {
    private Movie saved;

    @Override
    public void save(Movie movie) {
      saved = movie;
    }
  }

  private static final class FakeCatalogRepository implements CatalogQueryRepository {
    private Optional<Movie> movie = Optional.empty();

    @Override
    public List<Movie> findPublishedMovies() {
      return List.of();
    }

    @Override
    public List<Movie> searchMoviesByTitle(String titlePart) {
      return List.of();
    }

    @Override
    public Optional<Movie> findMovieById(MovieId id) {
      return movie.filter(m -> m.id().equals(id));
    }

    @Override
    public List<ScreeningWithMovie> findUpcomingScreenings(Instant from, Instant to) {
      return List.of();
    }

    @Override
    public List<ScreeningWithMovie> findScreeningsByMovie(MovieId movieId, Instant from) {
      return List.of();
    }

    @Override
    public Optional<ScreeningDetail> findScreeningDetail(ScreeningId id) {
      return Optional.empty();
    }
  }
}
