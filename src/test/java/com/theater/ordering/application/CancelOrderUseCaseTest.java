package com.theater.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.catalog.domain.CatalogQueryRepository;
import com.theater.catalog.domain.Movie;
import com.theater.catalog.domain.Screen;
import com.theater.catalog.domain.Screening;
import com.theater.catalog.domain.ScreeningStatus;
import com.theater.ordering.application.event.OrderCanceledEvent;
import com.theater.ordering.domain.Order;
import com.theater.ordering.domain.OrderRepository;
import com.theater.ordering.domain.OrderStatus;
import com.theater.ordering.domain.Payment;
import com.theater.ordering.domain.PaymentId;
import com.theater.ordering.domain.PaymentRepository;
import com.theater.ordering.domain.PaymentStatus;
import com.theater.ordering.domain.Refund;
import com.theater.ordering.domain.RefundRepository;
import com.theater.reservation.domain.Reservation;
import com.theater.reservation.domain.ReservationRepository;
import com.theater.reservation.domain.ReservationStatus;
import com.theater.reservation.domain.ScreeningCounterRepository;
import com.theater.reservation.domain.SeatState;
import com.theater.reservation.domain.SeatStateRepository;
import com.theater.shared.error.ConflictException;
import com.theater.shared.error.IllegalStateTransitionException;
import com.theater.shared.error.NotFoundException;
import com.theater.shared.eventbus.DomainEvent;
import com.theater.shared.eventbus.DomainEventBus;
import com.theater.shared.kernel.Currency;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CancelOrderUseCaseTest {

  static final Instant NOW = Instant.parse("2026-05-12T00:00:00Z");
  static final Instant SCREENING_START = NOW.plusSeconds(7200); // 2時間後
  static final OrderId ORDER_ID = new OrderId("order-1");
  static final UserId USER_ID = new UserId("user-1");
  static final ReservationId RESERVATION_ID = new ReservationId("reservation-1");
  static final ScreeningId SCREENING_ID = new ScreeningId("screening-1");
  static final Money TOTAL = new Money(3000L, Currency.JPY);

  FakeUnitOfWork uow = new FakeUnitOfWork();
  FakeOrderRepository orderRepo = new FakeOrderRepository();
  FakePaymentRepository paymentRepo = new FakePaymentRepository();
  FakeRefundRepository refundRepo = new FakeRefundRepository();
  FakeReservationRepository reservationRepo = new FakeReservationRepository();
  FakeSeatStateRepository seatStateRepo = new FakeSeatStateRepository();
  FakeTicketRepository ticketRepo = new FakeTicketRepository();
  FakeCounterRepository counterRepo = new FakeCounterRepository();
  FakeCatalogQueryRepository catalogQueryRepo = new FakeCatalogQueryRepository();
  FakeDomainEventBus eventBus = new FakeDomainEventBus();

  CancelOrderUseCase useCase;

  @BeforeEach
  void setup() {
    useCase =
        new CancelOrderUseCase(
            uow,
            new CancelOrderUseCase.Repositories(
                orderRepo,
                paymentRepo,
                refundRepo,
                reservationRepo,
                seatStateRepo,
                ticketRepo,
                counterRepo,
                catalogQueryRepo),
            eventBus,
            () -> NOW,
            () -> "gen-id");

    // デフォルト: CONFIRMED注文、上映前
    orderRepo.order = confirmedOrder();
    paymentRepo.payment = paidPayment();
    reservationRepo.reservation = confirmedReservation();
    catalogQueryRepo.startTime = SCREENING_START;
  }

  @Nested
  class HappyPath {

    @Test
    void cancel_confirmed_order_before_screening_succeeds() {
      useCase.execute(new CancelOrderUseCase.Command(ORDER_ID, USER_ID));

      assertThat(orderRepo.saved).hasSize(1);
      assertThat(orderRepo.saved.get(0).orderStatus()).isEqualTo(OrderStatus.CANCELED);
      assertThat(orderRepo.saved.get(0).paymentStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void cancel_creates_refund_record() {
      useCase.execute(new CancelOrderUseCase.Command(ORDER_ID, USER_ID));

      assertThat(refundRepo.saved).isNotNull();
      assertThat(refundRepo.saved.orderId()).isEqualTo(ORDER_ID);
      assertThat(refundRepo.saved.amount()).isEqualTo(TOTAL);
    }

    @Test
    void cancel_updates_payment_to_refunded() {
      useCase.execute(new CancelOrderUseCase.Command(ORDER_ID, USER_ID));

      assertThat(paymentRepo.saved).isNotNull();
      assertThat(paymentRepo.saved.status()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void cancel_releases_sold_seats() {
      useCase.execute(new CancelOrderUseCase.Command(ORDER_ID, USER_ID));

      assertThat(seatStateRepo.releaseSoldCalled).isTrue();
    }

    @Test
    void cancel_cancels_tickets() {
      useCase.execute(new CancelOrderUseCase.Command(ORDER_ID, USER_ID));

      assertThat(ticketRepo.cancelByOrderCalled).isTrue();
    }

    @Test
    void cancel_cancels_reservation() {
      useCase.execute(new CancelOrderUseCase.Command(ORDER_ID, USER_ID));

      assertThat(reservationRepo.saved).isNotNull();
      assertThat(reservationRepo.saved.status()).isEqualTo(ReservationStatus.CANCELED);
    }

    @Test
    void cancel_publishes_order_canceled_event() {
      useCase.execute(new CancelOrderUseCase.Command(ORDER_ID, USER_ID));

      assertThat(eventBus.events).hasSize(1);
      assertThat(eventBus.events.get(0)).isInstanceOf(OrderCanceledEvent.class);
      var event = (OrderCanceledEvent) eventBus.events.get(0);
      assertThat(event.orderId()).isEqualTo(ORDER_ID);
      assertThat(event.refundAmount()).isEqualTo(TOTAL);
    }
  }

  @Nested
  class Validation {

    @Test
    void order_not_found_throws_not_found() {
      orderRepo.order = null;

      assertThatThrownBy(() -> useCase.execute(new CancelOrderUseCase.Command(ORDER_ID, USER_ID)))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    void wrong_user_throws_illegal_state_transition() {
      assertThatThrownBy(
              () ->
                  useCase.execute(
                      new CancelOrderUseCase.Command(ORDER_ID, new UserId("other-user"))))
          .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void already_canceled_order_throws_illegal_state_transition() {
      orderRepo.order = canceledOrder();

      assertThatThrownBy(() -> useCase.execute(new CancelOrderUseCase.Command(ORDER_ID, USER_ID)))
          .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void already_refunded_throws_conflict() {
      refundRepo.existing = new Refund("rf-old", ORDER_ID, TOTAL, "already", NOW.minusSeconds(60));

      assertThatThrownBy(() -> useCase.execute(new CancelOrderUseCase.Command(ORDER_ID, USER_ID)))
          .isInstanceOf(ConflictException.class);
    }

    @Test
    void screening_already_started_throws_illegal_state_transition() {
      catalogQueryRepo.startTime = NOW.minusSeconds(1); // 過去

      assertThatThrownBy(() -> useCase.execute(new CancelOrderUseCase.Command(ORDER_ID, USER_ID)))
          .isInstanceOf(IllegalStateTransitionException.class);
    }
  }

  // ── domain helpers ──────────────────────────────────────────────────────

  static Order confirmedOrder() {
    return new Order(
        ORDER_ID,
        USER_ID,
        SCREENING_ID,
        RESERVATION_ID,
        TOTAL,
        PaymentStatus.PAID,
        OrderStatus.CONFIRMED,
        NOW.minusSeconds(60),
        null,
        NOW.minusSeconds(120),
        NOW.minusSeconds(60),
        1L);
  }

  static Order canceledOrder() {
    return new Order(
        ORDER_ID,
        USER_ID,
        SCREENING_ID,
        RESERVATION_ID,
        TOTAL,
        PaymentStatus.REFUNDED,
        OrderStatus.CANCELED,
        NOW.minusSeconds(60),
        NOW.minusSeconds(30),
        NOW.minusSeconds(120),
        NOW.minusSeconds(30),
        2L);
  }

  static Payment paidPayment() {
    return new Payment(
        new PaymentId("pay-1"),
        ORDER_ID,
        TOTAL,
        PaymentStatus.PAID,
        NOW.minusSeconds(60),
        NOW.minusSeconds(60),
        NOW.minusSeconds(60),
        0L);
  }

  static Reservation confirmedReservation() {
    return new Reservation(
        RESERVATION_ID,
        USER_ID,
        SCREENING_ID,
        ReservationStatus.CONFIRMED,
        null,
        NOW.minusSeconds(120),
        NOW.minusSeconds(60),
        1L);
  }

  // ── Fakes ───────────────────────────────────────────────────────────────

  static final class FakeUnitOfWork implements UnitOfWork {
    @Override
    public <R> R execute(Tx mode, java.util.function.Supplier<R> work) {
      return work.get();
    }

    @Override
    public Connection currentConnection() {
      throw new UnsupportedOperationException();
    }
  }

  static final class FakeOrderRepository implements OrderRepository {
    Order order;
    List<Order> saved = new ArrayList<>();

    @Override
    public Optional<Order> findById(OrderId id) {
      return Optional.ofNullable(order).filter(o -> o.id().equals(id));
    }

    @Override
    public Optional<Order> findByReservationId(ReservationId reservationId) {
      return Optional.empty();
    }

    @Override
    public List<Order> findByUser(UserId userId) {
      return List.of();
    }

    @Override
    public void save(Order o) {
      saved.add(o);
    }
  }

  static final class FakePaymentRepository implements PaymentRepository {
    Payment payment;
    Payment saved;

    @Override
    public Optional<Payment> findByOrderId(OrderId orderId) {
      return Optional.ofNullable(payment);
    }

    @Override
    public void save(Payment p) {
      saved = p;
    }
  }

  static final class FakeRefundRepository implements RefundRepository {
    Refund existing;
    Refund saved;

    @Override
    public Optional<Refund> findByOrderId(OrderId orderId) {
      return Optional.ofNullable(existing);
    }

    @Override
    public void save(Refund refund) {
      saved = refund;
    }
  }

  static final class FakeReservationRepository implements ReservationRepository {
    Reservation reservation;
    Reservation saved;

    @Override
    public Optional<Reservation> findById(ReservationId id) {
      return Optional.ofNullable(reservation);
    }

    @Override
    public List<Reservation> findActiveByUser(UserId userId) {
      return List.of();
    }

    @Override
    public List<com.theater.shared.kernel.ReservationId> findExpiring(Instant now, int limit) {
      return List.of();
    }

    @Override
    public void save(Reservation r) {
      saved = r;
    }
  }

  static final class FakeSeatStateRepository implements SeatStateRepository {
    boolean releaseSoldCalled;

    @Override
    public List<SeatState> findByScreening(ScreeningId screeningId) {
      return List.of();
    }

    @Override
    public int tryHold(
        ScreeningId sid, List<SeatId> seats, ReservationId rid, Instant expiresAt, Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int releaseByReservation(ReservationId rid, Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int releaseSoldByReservation(ReservationId rid, Instant now) {
      releaseSoldCalled = true;
      return 2;
    }

    @Override
    public void markSold(ReservationId rid, Map<SeatId, TicketId> seatToTicket, Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void markExpired(List<ReservationId> ids, Instant now) {
      throw new UnsupportedOperationException();
    }
  }

  static final class FakeTicketRepository implements TicketRepository {
    boolean cancelByOrderCalled;

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
      throw new UnsupportedOperationException();
    }

    @Override
    public void markUsed(TicketId id, Instant usedAt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int cancelByOrderId(OrderId orderId, Instant canceledAt) {
      cancelByOrderCalled = true;
      return 2;
    }
  }

  static final class FakeCounterRepository implements ScreeningCounterRepository {
    int lastAvailDelta;
    int lastSoldDelta;

    @Override
    public void adjust(ScreeningId sid, int availDelta, int resvDelta, int soldDelta, Instant now) {
      lastAvailDelta = availDelta;
      lastSoldDelta = soldDelta;
    }
  }

  static final class FakeCatalogQueryRepository implements CatalogQueryRepository {
    Instant startTime = SCREENING_START;

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
      Screening screening =
          new Screening(
              id,
              new MovieId("movie-1"),
              new ScreenId("screen-1"),
              startTime,
              startTime.plusSeconds(7200),
              NOW.minusSeconds(86400),
              startTime.minusSeconds(3600),
              ScreeningStatus.OPEN,
              false,
              0,
              0,
              2,
              NOW,
              NOW,
              NOW,
              0L);
      Movie movie = new Movie(new MovieId("movie-1"), "Test", "", 120, true, NOW, NOW, 0L);
      Screen screen = new Screen(new ScreenId("screen-1"), "Screen 1", 10, NOW, NOW);
      return Optional.of(new ScreeningDetail(screening, movie, screen));
    }
  }

  static final class FakeDomainEventBus implements DomainEventBus {
    List<DomainEvent> events = new ArrayList<>();

    @Override
    public void publish(DomainEvent event) {
      events.add(event);
    }
  }
}
