package com.theater.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.catalog.domain.CatalogQueryRepository;
import com.theater.catalog.domain.CatalogQueryRepository.ScreeningDetail;
import com.theater.catalog.domain.CatalogQueryRepository.ScreeningWithMovie;
import com.theater.catalog.domain.Movie;
import com.theater.catalog.domain.Screen;
import com.theater.catalog.domain.Screening;
import com.theater.catalog.domain.ScreeningStatus;
import com.theater.ordering.domain.Order;
import com.theater.ordering.domain.OrderRepository;
import com.theater.ordering.domain.OrderStatus;
import com.theater.ordering.domain.Payment;
import com.theater.ordering.domain.PaymentGateway;
import com.theater.ordering.domain.PaymentRepository;
import com.theater.ordering.domain.PaymentStatus;
import com.theater.reservation.domain.Reservation;
import com.theater.reservation.domain.ReservationRepository;
import com.theater.reservation.domain.ReservationStatus;
import com.theater.reservation.domain.ScreeningCounterRepository;
import com.theater.reservation.domain.SeatState;
import com.theater.reservation.domain.SeatStateRepository;
import com.theater.reservation.domain.SeatStateStatus;
import com.theater.shared.error.ConflictException;
import com.theater.shared.eventbus.DomainEvent;
import com.theater.shared.eventbus.DomainEventBus;
import com.theater.shared.kernel.Money;
import com.theater.shared.kernel.MovieId;
import com.theater.shared.kernel.OrderId;
import com.theater.shared.kernel.ReservationId;
import com.theater.shared.kernel.ScreenId;
import com.theater.shared.kernel.ScreeningId;
import com.theater.shared.kernel.SeatId;
import com.theater.shared.kernel.TicketId;
import com.theater.shared.kernel.UserId;
import com.theater.shared.tx.Tx;
import com.theater.shared.tx.UnitOfWork;
import com.theater.ticketing.domain.Ticket;
import com.theater.ticketing.domain.TicketRepository;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

final class CheckoutUseCaseTest {

  private static final Instant NOW = Instant.parse("2026-05-12T00:00:00Z");
  private static final ReservationId RESERVATION_ID = new ReservationId("reservation-1");
  private static final ScreeningId SCREENING_ID = new ScreeningId("screening-1");
  private static final UserId USER_ID = new UserId("user-1");

  private final FakeUnitOfWork uow = new FakeUnitOfWork();
  private final FakeReservationRepository reservationRepo = new FakeReservationRepository();
  private final FakeSeatStateRepository seatStateRepo = new FakeSeatStateRepository();
  private final FakeOrderRepository orderRepo = new FakeOrderRepository();
  private final FakePaymentRepository paymentRepo = new FakePaymentRepository();
  private final FakeTicketRepository ticketRepo = new FakeTicketRepository();
  private final FakeScreeningCounterRepository counterRepo = new FakeScreeningCounterRepository();
  private final FakeCatalogQueryRepository catalogQueryRepo = new FakeCatalogQueryRepository();
  private final FakePaymentGateway paymentGateway = new FakePaymentGateway();
  private final FakeDomainEventBus eventBus = new FakeDomainEventBus();
  private final Queue<String> ids =
      new ArrayDeque<>(List.of("order-1", "payment-1", "ticket-1", "ticket-2"));
  private final CheckoutUseCase useCase =
      new CheckoutUseCase(
          uow,
          new CheckoutUseCase.Repositories(
              reservationRepo,
              seatStateRepo,
              orderRepo,
              paymentRepo,
              ticketRepo,
              counterRepo,
              catalogQueryRepo),
          new CheckoutUseCase.Services(paymentGateway, eventBus),
          () -> NOW,
          ids::remove);

  @Test
  void confirms_order_payment_reservation_tickets_seats_counter_and_events_in_required_tx() {
    reservationRepo.reservation = Optional.of(holdReservation(USER_ID, NOW.plusSeconds(60)));
    seatStateRepo.seats = List.of(heldSeat("A-1", 1_800), heldSeat("A-2", 2_200));
    catalogQueryRepo.detail = Optional.of(screeningDetail());

    CheckoutUseCase.Result result =
        useCase.execute(new CheckoutUseCase.Command(RESERVATION_ID, USER_ID, Money.jpy(4_000)));

    assertThat(uow.lastMode()).isEqualTo(Tx.REQUIRED);
    assertThat(result.orderId()).isEqualTo(new OrderId("order-1"));
    assertThat(result.ticketIds())
        .containsExactly(new TicketId("ticket-1"), new TicketId("ticket-2"));
    assertThat(orderRepo.saved)
        .extracting(Order::orderStatus)
        .containsExactly(OrderStatus.CREATED, OrderStatus.CONFIRMED);
    assertThat(orderRepo.saved.get(1).paymentStatus()).isEqualTo(PaymentStatus.PAID);
    assertThat(paymentRepo.saved.status()).isEqualTo(PaymentStatus.PAID);
    assertThat(reservationRepo.saved.status()).isEqualTo(ReservationStatus.CONFIRMED);
    assertThat(ticketRepo.inserted).hasSize(2);
    assertThat(seatStateRepo.soldReservationId).isEqualTo(RESERVATION_ID);
    assertThat(counterRepo.reservedDelta).isEqualTo(-2);
    assertThat(counterRepo.soldDelta).isEqualTo(2);
    assertThat(eventBus.events)
        .extracting(DomainEvent::eventType)
        .containsExactly("OrderConfirmed", "TicketsIssued");
    assertThat(paymentGateway.callCount).isEqualTo(1);
  }

  @Test
  void expected_total_mismatch_rejects_before_payment_side_effects() {
    reservationRepo.reservation = Optional.of(holdReservation(USER_ID, NOW.plusSeconds(60)));
    seatStateRepo.seats = List.of(heldSeat("A-1", 1_800));
    catalogQueryRepo.detail = Optional.of(screeningDetail());

    assertThatThrownBy(
            () ->
                useCase.execute(
                    new CheckoutUseCase.Command(RESERVATION_ID, USER_ID, Money.jpy(999))))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Total amount changed");

    assertThat(paymentGateway.callCount).isZero();
    assertThat(paymentRepo.saved).isNull();
    assertThat(ticketRepo.inserted).isEmpty();
    assertThat(eventBus.events).isEmpty();
  }

  @Test
  void duplicate_checkout_is_rejected_before_payment() {
    reservationRepo.reservation = Optional.of(holdReservation(USER_ID, NOW.plusSeconds(60)));
    orderRepo.existing = Optional.of(existingOrder());

    assertThatThrownBy(
            () ->
                useCase.execute(
                    new CheckoutUseCase.Command(RESERVATION_ID, USER_ID, Money.jpy(1_800))))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Already checked out");

    assertThat(paymentGateway.callCount).isZero();
  }

  private static Reservation holdReservation(UserId userId, Instant expiresAt) {
    return new Reservation(
        RESERVATION_ID, userId, SCREENING_ID, ReservationStatus.HOLD, expiresAt, NOW, NOW, 0);
  }

  private static SeatState heldSeat(String seatId, int price) {
    return new SeatState(
        SCREENING_ID,
        new SeatId(seatId),
        SeatStateStatus.HOLD,
        RESERVATION_ID,
        NOW.plusSeconds(60),
        null,
        price,
        0,
        NOW);
  }

  private static Order existingOrder() {
    return new Order(
        new OrderId("existing-order"),
        USER_ID,
        SCREENING_ID,
        RESERVATION_ID,
        Money.jpy(1_800),
        PaymentStatus.PAID,
        OrderStatus.CONFIRMED,
        NOW,
        null,
        NOW,
        NOW,
        0);
  }

  private static ScreeningDetail screeningDetail() {
    MovieId movieId = new MovieId("movie-1");
    ScreenId screenId = new ScreenId("screen-1");
    Screening screening =
        new Screening(
            SCREENING_ID,
            movieId,
            screenId,
            NOW.plusSeconds(7_200),
            NOW.plusSeconds(12_000),
            NOW.minusSeconds(7_200),
            NOW.plusSeconds(3_600),
            ScreeningStatus.OPEN,
            false,
            48,
            2,
            0,
            NOW,
            NOW,
            NOW,
            0);
    Movie movie = new Movie(movieId, "River Line", "Quiet suspense.", 118, true, NOW, NOW, 0);
    Screen screen = new Screen(screenId, "Screen 1", 50, NOW, NOW);
    return new ScreeningDetail(screening, movie, screen);
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

  private static final class FakeReservationRepository implements ReservationRepository {
    private Optional<Reservation> reservation = Optional.empty();
    private Reservation saved;

    @Override
    public Optional<Reservation> findById(ReservationId id) {
      return reservation.filter(r -> r.id().equals(id));
    }

    @Override
    public List<Reservation> findActiveByUser(UserId userId) {
      return List.of();
    }

    @Override
    public List<ReservationId> findExpiring(Instant now, int limit) {
      return List.of();
    }

    @Override
    public void save(Reservation reservation) {
      saved = reservation;
    }
  }

  private static final class FakeSeatStateRepository implements SeatStateRepository {
    private List<SeatState> seats = List.of();
    private ReservationId soldReservationId;

    @Override
    public List<SeatState> findByScreening(ScreeningId screeningId) {
      return seats.stream().filter(s -> s.screeningId().equals(screeningId)).toList();
    }

    @Override
    public int tryHold(
        ScreeningId screeningId,
        List<SeatId> seats,
        ReservationId reservationId,
        Instant expiresAt,
        Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int releaseByReservation(ReservationId reservationId, Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void markSold(
        ReservationId reservationId, Map<SeatId, TicketId> seatToTicket, Instant now) {
      soldReservationId = reservationId;
    }

    @Override
    public void markExpired(List<ReservationId> reservationIds, Instant now) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class FakeOrderRepository implements OrderRepository {
    private Optional<Order> existing = Optional.empty();
    private final List<Order> saved = new ArrayList<>();

    @Override
    public Optional<Order> findById(OrderId id) {
      return Optional.empty();
    }

    @Override
    public Optional<Order> findByReservationId(ReservationId reservationId) {
      return existing.filter(o -> o.reservationId().equals(reservationId));
    }

    @Override
    public List<Order> findByUser(UserId userId) {
      return List.of();
    }

    @Override
    public void save(Order order) {
      saved.add(order);
    }
  }

  private static final class FakePaymentRepository implements PaymentRepository {
    private Payment saved;

    @Override
    public Optional<Payment> findByOrderId(OrderId orderId) {
      return Optional.empty();
    }

    @Override
    public void save(Payment payment) {
      saved = payment;
    }
  }

  private static final class FakeTicketRepository implements TicketRepository {
    private final List<Ticket> inserted = new ArrayList<>();

    @Override
    public Optional<Ticket> findById(TicketId id) {
      return Optional.empty();
    }

    @Override
    public List<Ticket> findByUser(UserId userId) {
      return List.of();
    }

    @Override
    public void insert(Ticket ticket) {
      inserted.add(ticket);
    }

    @Override
    public void markUsed(TicketId id, Instant usedAt) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class FakeScreeningCounterRepository implements ScreeningCounterRepository {
    private int reservedDelta;
    private int soldDelta;

    @Override
    public void adjust(
        ScreeningId screeningId,
        int availableDelta,
        int reservedDelta,
        int soldDelta,
        Instant now) {
      this.reservedDelta = reservedDelta;
      this.soldDelta = soldDelta;
    }
  }

  private static final class FakeCatalogQueryRepository implements CatalogQueryRepository {
    private Optional<ScreeningDetail> detail = Optional.empty();

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
      return Optional.empty();
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
      return detail.filter(d -> d.screening().id().equals(id));
    }
  }

  private static final class FakePaymentGateway implements PaymentGateway {
    private int callCount;

    @Override
    public PaymentResult charge(Money amount) {
      callCount++;
      return new PaymentResult("external-tx-1", NOW);
    }
  }

  private static final class FakeDomainEventBus implements DomainEventBus {
    private final List<DomainEvent> events = new ArrayList<>();

    @Override
    public void publish(DomainEvent event) {
      events.add(event);
    }
  }
}
