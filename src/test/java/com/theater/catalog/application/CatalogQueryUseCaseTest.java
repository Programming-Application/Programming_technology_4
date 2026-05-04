package com.theater.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.catalog.domain.CatalogQueryRepository;
import com.theater.catalog.domain.CatalogQueryRepository.ScreeningDetail;
import com.theater.catalog.domain.CatalogQueryRepository.ScreeningWithMovie;
import com.theater.catalog.domain.Movie;
import com.theater.catalog.domain.MovieId;
import com.theater.catalog.domain.Screen;
import com.theater.catalog.domain.ScreenId;
import com.theater.catalog.domain.Screening;
import com.theater.catalog.domain.ScreeningId;
import com.theater.catalog.domain.ScreeningStatus;
import com.theater.shared.error.NotFoundException;
import com.theater.shared.kernel.Clock;
import com.theater.shared.tx.Tx;
import com.theater.shared.tx.UnitOfWork;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

final class CatalogQueryUseCaseTest {

  private static final Instant NOW = Instant.parse("2026-05-03T00:00:00Z");
  private final FakeUnitOfWork uow = new FakeUnitOfWork();
  private final FakeCatalogRepository repository = new FakeCatalogRepository();
  private final Clock clock = () -> NOW;

  @Test
  void listPublishedMovies_returns_only_repository_movies() {
    var useCase = new ListPublishedMoviesUseCase(uow, repository);

    var result = useCase.execute(new ListPublishedMoviesUseCase.Command());

    assertThat(result).extracting(MovieSummary::title).containsExactly("River Line");
    assertThat(uow.lastMode()).isEqualTo(Tx.READ_ONLY);
  }

  @Test
  void searchMovies_when_title_part_is_null_treats_as_blank() {
    var useCase = new SearchMoviesUseCase(uow, repository);

    var result = useCase.execute(new SearchMoviesUseCase.Command(null));

    assertThat(result).extracting(MovieSummary::movieId).containsExactly("movie-1");
    assertThat(repository.lastSearch()).isEmpty();
  }

  @Test
  void listUpcomingScreenings_uses_one_week_window_from_clock() {
    var useCase = new ListUpcomingScreeningsUseCase(uow, repository, clock);

    var result = useCase.execute(new ListUpcomingScreeningsUseCase.Command());

    assertThat(result).extracting(ScreeningSummary::screeningId).containsExactly("screening-1");
    assertThat(repository.lastUpcomingFrom()).isEqualTo(NOW);
    assertThat(repository.lastUpcomingTo()).isEqualTo(NOW.plusSeconds(7 * 24 * 60 * 60));
  }

  @Test
  void getMovieDetail_when_movie_exists_returns_upcoming_screenings() {
    var useCase = new GetMovieDetailUseCase(uow, repository, clock);

    var result = useCase.execute(new GetMovieDetailUseCase.Command("movie-1"));

    assertThat(result.title()).isEqualTo("River Line");
    assertThat(result.upcomingScreenings())
        .extracting(ScreeningSummary::screeningId)
        .containsExactly("screening-1");
  }

  @Test
  void getMovieDetail_when_movie_missing_throws_not_found() {
    var useCase = new GetMovieDetailUseCase(uow, repository, clock);

    assertThatThrownBy(() -> useCase.execute(new GetMovieDetailUseCase.Command("missing")))
        .isInstanceOf(NotFoundException.class)
        .hasMessageContaining("Movie");
  }

  @Test
  void getScreeningDetail_returns_seat_counters() {
    var useCase = new GetScreeningDetailUseCase(uow, repository);

    var result = useCase.execute(new GetScreeningDetailUseCase.Command("screening-1"));

    assertThat(result.availableSeatCount()).isEqualTo(90);
    assertThat(result.reservedSeatCount()).isEqualTo(5);
    assertThat(result.soldSeatCount()).isEqualTo(5);
  }

  private static Movie movie() {
    return new Movie(
        new MovieId("movie-1"), "River Line", "Quiet suspense.", 118, true, NOW, NOW, 0);
  }

  private static Screen screen() {
    return new Screen(new ScreenId("screen-1"), "Screen 1", 100, NOW, NOW);
  }

  private static Screening screening() {
    return new Screening(
        new ScreeningId("screening-1"),
        new MovieId("movie-1"),
        new ScreenId("screen-1"),
        NOW.plusSeconds(3_600),
        NOW.plusSeconds(10_680),
        NOW.minusSeconds(86_400),
        NOW.plusSeconds(1_800),
        ScreeningStatus.OPEN,
        false,
        90,
        5,
        5,
        NOW,
        NOW,
        NOW,
        0);
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

  private static final class FakeCatalogRepository implements CatalogQueryRepository {
    private Instant lastUpcomingFrom;
    private Instant lastUpcomingTo;
    private String lastSearch;

    @Override
    public List<Movie> findPublishedMovies() {
      return List.of(movie());
    }

    @Override
    public List<Movie> searchMoviesByTitle(String titlePart) {
      lastSearch = titlePart;
      return List.of(movie());
    }

    @Override
    public Optional<Movie> findMovieById(MovieId id) {
      return "movie-1".equals(id.value()) ? Optional.of(movie()) : Optional.empty();
    }

    @Override
    public List<ScreeningWithMovie> findUpcomingScreenings(Instant from, Instant to) {
      lastUpcomingFrom = from;
      lastUpcomingTo = to;
      return List.of(new ScreeningWithMovie(screening(), movie().title(), screen().name(), 118));
    }

    @Override
    public List<ScreeningWithMovie> findScreeningsByMovie(MovieId movieId, Instant from) {
      return List.of(new ScreeningWithMovie(screening(), movie().title(), screen().name(), 118));
    }

    @Override
    public Optional<ScreeningDetail> findScreeningDetail(ScreeningId id) {
      return "screening-1".equals(id.value())
          ? Optional.of(new ScreeningDetail(screening(), movie(), screen()))
          : Optional.empty();
    }

    Instant lastUpcomingFrom() {
      return lastUpcomingFrom;
    }

    Instant lastUpcomingTo() {
      return lastUpcomingTo;
    }

    String lastSearch() {
      return lastSearch;
    }
  }
}
