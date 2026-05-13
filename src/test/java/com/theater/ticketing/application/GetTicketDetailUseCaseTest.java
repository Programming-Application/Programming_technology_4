package com.theater.ticketing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.catalog.domain.CatalogQueryRepository;
import com.theater.catalog.domain.Movie;
import com.theater.catalog.domain.Screen;
import com.theater.catalog.domain.Screening;
import com.theater.catalog.domain.ScreeningStatus;
import com.theater.shared.error.NotFoundException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link GetTicketDetailUseCase} の Unit Test。
 *
 * <p>NotFound / catalog 解決 / READ_ONLY Tx を fake で検証する。
 */
class GetTicketDetailUseCaseTest {

  private static final Instant NOW = Instant.parse("2026-05-13T00:00:00Z");
  private static final Instant START = NOW.plusSeconds(7200);
  private static final Instant END = START.plusSeconds(7080);
  private static final UserId ALICE = new UserId("u-alice");
  private static final TicketId TICKET_1 = new TicketId("t-1");
  private static final ScreeningId SCREENING_1 = new ScreeningId("sc-1");
  private static final MovieId MOVIE_1 = new MovieId("m-1");
  private static final ScreenId SCREEN_1 = new ScreenId("screen-1");

  private InMemoryTicketRepository tickets;
  private FakeCatalogQueryRepository catalog;

  private GetTicketDetailUseCase newUseCase() {
    return new GetTicketDetailUseCase(new NoOpUnitOfWork(), tickets, catalog);
  }

  private static Ticket activeTicket(TicketId id, UserId user, String seat) {
    return new Ticket(
        id,
        new OrderId("o-" + id.value()),
        SCREENING_1,
        MOVIE_1,
        SCREEN_1,
        new SeatId(seat),
        user,
        new Money(1500L, Currency.JPY),
        TicketStatus.ACTIVE,
        NOW,
        null,
        null,
        NOW,
        NOW,
        0L);
  }

  @Nested
  class Handle {

    @Test
    void returns_detail_view_with_catalog_enrichment() {
      tickets = new InMemoryTicketRepository();
      tickets.save(activeTicket(TICKET_1, ALICE, "B7"));
      catalog = new FakeCatalogQueryRepository();
      catalog.put(SCREENING_1, MOVIE_1, "Inception");

      TicketDetailView view = newUseCase().execute(new GetTicketDetailUseCase.Command(TICKET_1));

      assertThat(view.ticketId()).isEqualTo(TICKET_1);
      assertThat(view.orderId().value()).isEqualTo("o-t-1");
      assertThat(view.movieTitle()).isEqualTo("Inception");
      assertThat(view.screenName()).isEqualTo("Screen 1");
      assertThat(view.screeningStartTime()).isEqualTo(START);
      assertThat(view.screeningEndTime()).isEqualTo(END);
      assertThat(view.seatLabel()).isEqualTo("B7");
      assertThat(view.price()).isEqualTo(new Money(1500L, Currency.JPY));
      assertThat(view.status()).isEqualTo(TicketStatus.ACTIVE);
      assertThat(view.purchasedAt()).isEqualTo(NOW);
      assertThat(view.usedAt()).isNull();
    }

    @Test
    void unknown_ticket_throws_not_found() {
      tickets = new InMemoryTicketRepository();
      catalog = new FakeCatalogQueryRepository();

      assertThatThrownBy(
              () ->
                  newUseCase()
                      .execute(new GetTicketDetailUseCase.Command(new TicketId("t-missing"))))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining("t-missing");
    }

    @Test
    void missing_screening_in_catalog_is_state_error() {
      tickets = new InMemoryTicketRepository();
      tickets.save(activeTicket(TICKET_1, ALICE, "A1"));
      catalog = new FakeCatalogQueryRepository();
      // 意図的に screening を入れない (FK 違反相当の不整合)

      assertThatThrownBy(() -> newUseCase().execute(new GetTicketDetailUseCase.Command(TICKET_1)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(SCREENING_1.value());
    }
  }

  @Nested
  class CommandValidation {

    @Test
    void null_ticket_id_rejected() {
      assertThatThrownBy(() -> new GetTicketDetailUseCase.Command(null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  class TxMode {

    @Test
    void get_detail_runs_in_read_only_tx() {
      tickets = new InMemoryTicketRepository();
      tickets.save(activeTicket(TICKET_1, ALICE, "A1"));
      catalog = new FakeCatalogQueryRepository();
      catalog.put(SCREENING_1, MOVIE_1, "Movie");
      RecordingUnitOfWork recording = new RecordingUnitOfWork();
      GetTicketDetailUseCase uc = new GetTicketDetailUseCase(recording, tickets, catalog);

      uc.execute(new GetTicketDetailUseCase.Command(TICKET_1));

      assertThat(recording.lastTx).isEqualTo(Tx.READ_ONLY);
    }
  }

  // ---- fakes ----

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

    @Override
    public int cancelByOrderId(OrderId orderId, Instant canceledAt) {
      throw new UnsupportedOperationException("not used in this test");
    }
  }

  static final class FakeCatalogQueryRepository implements CatalogQueryRepository {
    private final Map<ScreeningId, ScreeningDetail> store = new LinkedHashMap<>();

    void put(ScreeningId screeningId, MovieId movieId, String title) {
      Screening screening =
          new Screening(
              screeningId,
              movieId,
              SCREEN_1,
              START,
              END,
              START.minusSeconds(86400),
              START.minusSeconds(60),
              ScreeningStatus.OPEN,
              false,
              50,
              0,
              0,
              NOW,
              NOW,
              NOW,
              0L);
      Movie movie = new Movie(movieId, title, "desc", 118, true, NOW, NOW, 0L);
      Screen screen = new Screen(SCREEN_1, "Screen 1", 100, NOW, NOW);
      store.put(screeningId, new ScreeningDetail(screening, movie, screen));
    }

    @Override
    public Optional<ScreeningDetail> findScreeningDetail(ScreeningId id) {
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
