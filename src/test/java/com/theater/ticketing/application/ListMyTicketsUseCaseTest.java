package com.theater.ticketing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.catalog.domain.CatalogQueryRepository;
import com.theater.catalog.domain.Movie;
import com.theater.catalog.domain.Screen;
import com.theater.catalog.domain.Screening;
import com.theater.catalog.domain.ScreeningStatus;
import com.theater.shared.kernel.Currency;
import com.theater.shared.kernel.Money;
import com.theater.shared.kernel.MovieId;
import com.theater.shared.kernel.OrderId;
import com.theater.shared.kernel.ScreenId;
import com.theater.shared.kernel.ScreeningId;
import com.theater.shared.kernel.SeatId;
import com.theater.shared.kernel.TicketId;
import com.theater.shared.kernel.UserId;
import com.theater.shared.tx.Tx;
import com.theater.shared.tx.UnitOfWork;
import com.theater.ticketing.domain.Ticket;
import com.theater.ticketing.domain.TicketRepository;
import com.theater.ticketing.domain.TicketStatus;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link ListMyTicketsUseCase} の Unit Test。
 *
 * <p>純粋 application ロジック (所有者フィルタ / status フィルタ / catalog 解決 / READ_ONLY Tx) を fake で検証する。 実 SQL は
 * {@code JdbcTicketRepositoryIT} (TK-01) 側に分離。
 */
class ListMyTicketsUseCaseTest {

  private static final Instant NOW = Instant.parse("2026-05-13T00:00:00Z");
  private static final UserId ALICE = new UserId("u-alice");
  private static final UserId BOB = new UserId("u-bob");
  private static final ScreeningId SCREENING_1 = new ScreeningId("sc-1");
  private static final ScreeningId SCREENING_2 = new ScreeningId("sc-2");
  private static final MovieId MOVIE_1 = new MovieId("m-1");
  private static final MovieId MOVIE_2 = new MovieId("m-2");
  private static final ScreenId SCREEN_1 = new ScreenId("screen-1");

  private InMemoryTicketRepository tickets;
  private FakeCatalogQueryRepository catalog;

  private ListMyTicketsUseCase newUseCase() {
    return new ListMyTicketsUseCase(new NoOpUnitOfWork(), tickets, catalog);
  }

  private static Ticket ticket(
      String id,
      UserId user,
      ScreeningId screeningId,
      MovieId movieId,
      String seat,
      TicketStatus status) {
    Instant usedAt = status == TicketStatus.USED ? NOW : null;
    Instant canceledAt = status == TicketStatus.CANCELED ? NOW : null;
    return new Ticket(
        new TicketId(id),
        new OrderId("o-" + id),
        screeningId,
        movieId,
        SCREEN_1,
        new SeatId(seat),
        user,
        new Money(1500L, Currency.JPY),
        status,
        NOW,
        usedAt,
        canceledAt,
        NOW,
        NOW,
        0L);
  }

  @Nested
  class Handle {

    @Test
    void returns_only_active_tickets_by_default() {
      tickets = new InMemoryTicketRepository();
      tickets.save(ticket("t-1", ALICE, SCREENING_1, MOVIE_1, "A1", TicketStatus.ACTIVE));
      tickets.save(ticket("t-2", ALICE, SCREENING_1, MOVIE_1, "A2", TicketStatus.USED));
      tickets.save(ticket("t-3", ALICE, SCREENING_1, MOVIE_1, "A3", TicketStatus.CANCELED));
      catalog = catalogWith(SCREENING_1, MOVIE_1, "Inception", NOW.plusSeconds(7200));

      List<TicketSummary> result =
          newUseCase().execute(new ListMyTicketsUseCase.Command(ALICE, null));

      assertThat(result).hasSize(1);
      assertThat(result.get(0).ticketId().value()).isEqualTo("t-1");
      assertThat(result.get(0).status()).isEqualTo(TicketStatus.ACTIVE);
    }

    @Test
    void status_filter_returns_only_requested_statuses() {
      tickets = new InMemoryTicketRepository();
      tickets.save(ticket("t-1", ALICE, SCREENING_1, MOVIE_1, "A1", TicketStatus.ACTIVE));
      tickets.save(ticket("t-2", ALICE, SCREENING_1, MOVIE_1, "A2", TicketStatus.USED));
      tickets.save(ticket("t-3", ALICE, SCREENING_1, MOVIE_1, "A3", TicketStatus.CANCELED));
      catalog = catalogWith(SCREENING_1, MOVIE_1, "Inception", NOW.plusSeconds(7200));

      List<TicketSummary> result =
          newUseCase()
              .execute(
                  new ListMyTicketsUseCase.Command(
                      ALICE, EnumSet.of(TicketStatus.USED, TicketStatus.CANCELED)));

      assertThat(result).extracting(s -> s.ticketId().value()).containsExactly("t-2", "t-3");
    }

    @Test
    void all_statuses_helper_returns_every_ticket() {
      tickets = new InMemoryTicketRepository();
      tickets.save(ticket("t-1", ALICE, SCREENING_1, MOVIE_1, "A1", TicketStatus.ACTIVE));
      tickets.save(ticket("t-2", ALICE, SCREENING_1, MOVIE_1, "A2", TicketStatus.USED));
      catalog = catalogWith(SCREENING_1, MOVIE_1, "Inception", NOW.plusSeconds(7200));

      List<TicketSummary> result =
          newUseCase().execute(ListMyTicketsUseCase.Command.allStatuses(ALICE));

      assertThat(result).hasSize(2);
    }

    @Test
    void does_not_return_other_users_tickets() {
      tickets = new InMemoryTicketRepository();
      tickets.save(ticket("t-1", ALICE, SCREENING_1, MOVIE_1, "A1", TicketStatus.ACTIVE));
      tickets.save(ticket("t-2", BOB, SCREENING_1, MOVIE_1, "B1", TicketStatus.ACTIVE));
      catalog = catalogWith(SCREENING_1, MOVIE_1, "Inception", NOW.plusSeconds(7200));

      List<TicketSummary> result =
          newUseCase().execute(ListMyTicketsUseCase.Command.activeOnly(ALICE));

      assertThat(result).hasSize(1);
      assertThat(result.get(0).ticketId().value()).isEqualTo("t-1");
    }

    @Test
    void empty_list_when_user_has_no_tickets() {
      tickets = new InMemoryTicketRepository();
      catalog = catalogWith(SCREENING_1, MOVIE_1, "Inception", NOW.plusSeconds(7200));

      List<TicketSummary> result =
          newUseCase().execute(ListMyTicketsUseCase.Command.activeOnly(ALICE));

      assertThat(result).isEmpty();
    }

    @Test
    void enriches_summary_with_catalog_data() {
      tickets = new InMemoryTicketRepository();
      tickets.save(ticket("t-1", ALICE, SCREENING_1, MOVIE_1, "A5", TicketStatus.ACTIVE));
      catalog = catalogWith(SCREENING_1, MOVIE_1, "Interstellar", NOW.plusSeconds(3600));

      TicketSummary s = newUseCase().execute(ListMyTicketsUseCase.Command.activeOnly(ALICE)).get(0);

      assertThat(s.movieTitle()).isEqualTo("Interstellar");
      assertThat(s.screenName()).isEqualTo("Screen 1");
      assertThat(s.screeningStartTime()).isEqualTo(NOW.plusSeconds(3600));
      assertThat(s.seatLabel()).isEqualTo("A5");
    }

    @Test
    void screening_cache_avoids_duplicate_catalog_lookups() {
      tickets = new InMemoryTicketRepository();
      tickets.save(ticket("t-1", ALICE, SCREENING_1, MOVIE_1, "A1", TicketStatus.ACTIVE));
      tickets.save(ticket("t-2", ALICE, SCREENING_1, MOVIE_1, "A2", TicketStatus.ACTIVE));
      tickets.save(ticket("t-3", ALICE, SCREENING_2, MOVIE_2, "B1", TicketStatus.ACTIVE));
      catalog = new FakeCatalogQueryRepository();
      catalog.put(SCREENING_1, MOVIE_1, "Movie One", NOW.plusSeconds(3600));
      catalog.put(SCREENING_2, MOVIE_2, "Movie Two", NOW.plusSeconds(7200));

      newUseCase().execute(ListMyTicketsUseCase.Command.activeOnly(ALICE));

      // 3 tickets, 2 unique screenings → catalog は各 screening 1 回ずつのみ呼ばれる
      assertThat(catalog.callCount(SCREENING_1)).isEqualTo(1);
      assertThat(catalog.callCount(SCREENING_2)).isEqualTo(1);
    }

    @Test
    void throws_if_catalog_has_no_screening_for_ticket() {
      tickets = new InMemoryTicketRepository();
      tickets.save(ticket("t-1", ALICE, SCREENING_1, MOVIE_1, "A1", TicketStatus.ACTIVE));
      catalog = new FakeCatalogQueryRepository();
      // 意図的に screening を入れない

      assertThatThrownBy(() -> newUseCase().execute(ListMyTicketsUseCase.Command.activeOnly(ALICE)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(SCREENING_1.value());
    }
  }

  @Nested
  class CommandValidation {

    @Test
    void null_user_id_rejected() {
      assertThatThrownBy(() -> new ListMyTicketsUseCase.Command(null, null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void empty_status_set_defaults_to_active_only() {
      ListMyTicketsUseCase.Command cmd =
          new ListMyTicketsUseCase.Command(ALICE, EnumSet.noneOf(TicketStatus.class));
      assertThat(cmd.statuses()).containsExactly(TicketStatus.ACTIVE);
    }

    @Test
    void command_statuses_is_defensively_copied() {
      EnumSet<TicketStatus> original = EnumSet.of(TicketStatus.ACTIVE);
      ListMyTicketsUseCase.Command cmd = new ListMyTicketsUseCase.Command(ALICE, original);
      original.add(TicketStatus.USED);
      assertThat(cmd.statuses()).containsExactly(TicketStatus.ACTIVE);
    }
  }

  @Nested
  class TxMode {

    @Test
    void list_runs_in_read_only_tx() {
      tickets = new InMemoryTicketRepository();
      catalog = new FakeCatalogQueryRepository();
      RecordingUnitOfWork recording = new RecordingUnitOfWork();
      ListMyTicketsUseCase uc = new ListMyTicketsUseCase(recording, tickets, catalog);

      uc.execute(ListMyTicketsUseCase.Command.activeOnly(ALICE));

      assertThat(recording.lastTx).isEqualTo(Tx.READ_ONLY);
    }
  }

  // ---- fakes ----

  private static FakeCatalogQueryRepository catalogWith(
      ScreeningId screeningId, MovieId movieId, String title, Instant startTime) {
    FakeCatalogQueryRepository c = new FakeCatalogQueryRepository();
    c.put(screeningId, movieId, title, startTime);
    return c;
  }

  static final class InMemoryTicketRepository implements TicketRepository {
    private final Map<TicketId, Ticket> store = new LinkedHashMap<>();

    void save(Ticket t) {
      store.put(t.id(), t);
    }

    @Override
    public Optional<Ticket> findById(TicketId id) {
      return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Ticket> findByUser(UserId userId) {
      List<Ticket> out = new ArrayList<>();
      for (Ticket t : store.values()) {
        if (t.userId().equals(userId)) {
          out.add(t);
        }
      }
      return out;
    }

    @Override
    public void insert(Ticket ticket) {
      save(ticket);
    }

    @Override
    public void markUsed(TicketId id, Instant usedAt) {
      throw new UnsupportedOperationException("not used in this test");
    }
  }

  /**
   * 必要な {@code findScreeningDetail} のみ実装。他のメソッドは呼ばれない前提で {@link UnsupportedOperationException}
   * を投げる。
   */
  static final class FakeCatalogQueryRepository implements CatalogQueryRepository {
    private final Map<ScreeningId, ScreeningDetail> store = new LinkedHashMap<>();
    private final Map<ScreeningId, Integer> calls = new LinkedHashMap<>();

    void put(ScreeningId screeningId, MovieId movieId, String title, Instant startTime) {
      Screening screening =
          new Screening(
              screeningId,
              movieId,
              SCREEN_1,
              startTime,
              startTime.plusSeconds(7080),
              startTime.minusSeconds(86400),
              startTime.minusSeconds(60),
              ScreeningStatus.OPEN,
              false,
              50,
              0,
              0,
              NOW,
              NOW,
              NOW,
              0L);
      Movie movie = new Movie(movieId, title, "description", 118, true, NOW, NOW, 0L);
      Screen screen = new Screen(SCREEN_1, "Screen 1", 100, NOW, NOW);
      store.put(screeningId, new ScreeningDetail(screening, movie, screen));
    }

    int callCount(ScreeningId id) {
      return calls.getOrDefault(id, 0);
    }

    @Override
    public Optional<ScreeningDetail> findScreeningDetail(ScreeningId id) {
      calls.merge(id, 1, Integer::sum);
      return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Movie> findPublishedMovies() {
      throw new UnsupportedOperationException("not used in this test");
    }

    @Override
    public List<Movie> searchMoviesByTitle(String titlePart) {
      throw new UnsupportedOperationException("not used in this test");
    }

    @Override
    public Optional<Movie> findMovieById(MovieId id) {
      throw new UnsupportedOperationException("not used in this test");
    }

    @Override
    public List<ScreeningWithMovie> findUpcomingScreenings(Instant from, Instant to) {
      throw new UnsupportedOperationException("not used in this test");
    }

    @Override
    public List<ScreeningWithMovie> findScreeningsByMovie(MovieId movieId, Instant from) {
      throw new UnsupportedOperationException("not used in this test");
    }
  }

  private static final class NoOpUnitOfWork implements UnitOfWork {
    @Override
    public <R> R execute(Tx mode, Supplier<R> work) {
      return work.get();
    }

    @Override
    public Connection currentConnection() {
      throw new IllegalStateException("currentConnection is unused in unit test");
    }
  }

  private static final class RecordingUnitOfWork implements UnitOfWork {
    Tx lastTx;

    @Override
    public <R> R execute(Tx mode, Supplier<R> work) {
      this.lastTx = mode;
      return work.get();
    }

    @Override
    public Connection currentConnection() {
      throw new IllegalStateException("currentConnection is unused in unit test");
    }
  }
}
