package com.theater.ordering.application;

import com.theater.catalog.domain.CatalogQueryRepository;
import com.theater.catalog.domain.CatalogQueryRepository.ScreeningDetail;
import com.theater.ordering.application.event.OrderConfirmedEvent;
import com.theater.ordering.application.event.TicketsIssuedEvent;
import com.theater.ordering.domain.Order;
import com.theater.ordering.domain.OrderRepository;
import com.theater.ordering.domain.OrderStatus;
import com.theater.ordering.domain.Payment;
import com.theater.ordering.domain.PaymentGateway;
import com.theater.ordering.domain.PaymentId;
import com.theater.ordering.domain.PaymentRepository;
import com.theater.ordering.domain.PaymentStatus;
import com.theater.reservation.domain.Reservation;
import com.theater.reservation.domain.ReservationRepository;
import com.theater.reservation.domain.ReservationStatus;
import com.theater.reservation.domain.ScreeningCounterRepository;
import com.theater.reservation.domain.SeatState;
import com.theater.reservation.domain.SeatStateRepository;
import com.theater.shared.error.ConflictException;
import com.theater.shared.error.IllegalStateTransitionException;
import com.theater.shared.error.NotFoundException;
import com.theater.shared.eventbus.DomainEventBus;
import com.theater.shared.kernel.Clock;
import com.theater.shared.kernel.Currency;
import com.theater.shared.kernel.IdGenerator;
import com.theater.shared.kernel.Money;
import com.theater.shared.kernel.OrderId;
import com.theater.shared.kernel.ReservationId;
import com.theater.shared.kernel.SeatId;
import com.theater.shared.kernel.TicketId;
import com.theater.shared.kernel.UserId;
import com.theater.shared.tx.TransactionalUseCase;
import com.theater.shared.tx.UnitOfWork;
import com.theater.ticketing.domain.Ticket;
import com.theater.ticketing.domain.TicketRepository;
import com.theater.ticketing.domain.TicketStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** HOLD 中の予約を注文・決済・発券まで 1 Tx で確定する use case。 */
public final class CheckoutUseCase
    extends TransactionalUseCase<CheckoutUseCase.Command, CheckoutUseCase.Result> {

  private final ReservationRepository reservationRepo;
  private final SeatStateRepository seatStateRepo;
  private final OrderRepository orderRepo;
  private final PaymentRepository paymentRepo;
  private final TicketRepository ticketRepo;
  private final ScreeningCounterRepository counterRepo;
  private final CatalogQueryRepository catalogQueryRepo;
  private final PaymentGateway paymentGateway;
  private final DomainEventBus eventBus;
  private final Clock clock;
  private final IdGenerator ids;

  public CheckoutUseCase(
      UnitOfWork uow, Repositories repositories, Services services, Clock clock, IdGenerator ids) {
    super(uow);
    Objects.requireNonNull(repositories, "repositories");
    Objects.requireNonNull(services, "services");
    this.reservationRepo = repositories.reservationRepo();
    this.seatStateRepo = repositories.seatStateRepo();
    this.orderRepo = repositories.orderRepo();
    this.paymentRepo = repositories.paymentRepo();
    this.ticketRepo = repositories.ticketRepo();
    this.counterRepo = repositories.counterRepo();
    this.catalogQueryRepo = repositories.catalogQueryRepo();
    this.paymentGateway = services.paymentGateway();
    this.eventBus = services.eventBus();
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ids = Objects.requireNonNull(ids, "ids");
  }

  public record Repositories(
      ReservationRepository reservationRepo,
      SeatStateRepository seatStateRepo,
      OrderRepository orderRepo,
      PaymentRepository paymentRepo,
      TicketRepository ticketRepo,
      ScreeningCounterRepository counterRepo,
      CatalogQueryRepository catalogQueryRepo) {
    public Repositories {
      Objects.requireNonNull(reservationRepo, "reservationRepo");
      Objects.requireNonNull(seatStateRepo, "seatStateRepo");
      Objects.requireNonNull(orderRepo, "orderRepo");
      Objects.requireNonNull(paymentRepo, "paymentRepo");
      Objects.requireNonNull(ticketRepo, "ticketRepo");
      Objects.requireNonNull(counterRepo, "counterRepo");
      Objects.requireNonNull(catalogQueryRepo, "catalogQueryRepo");
    }
  }

  public record Services(PaymentGateway paymentGateway, DomainEventBus eventBus) {
    public Services {
      Objects.requireNonNull(paymentGateway, "paymentGateway");
      Objects.requireNonNull(eventBus, "eventBus");
    }
  }

  public record Command(ReservationId reservationId, UserId userId, Money expectedTotal) {
    public Command {
      Objects.requireNonNull(reservationId, "reservationId");
      Objects.requireNonNull(userId, "userId");
      Objects.requireNonNull(expectedTotal, "expectedTotal");
    }
  }

  public record Result(OrderId orderId, List<TicketId> ticketIds) {
    public Result {
      Objects.requireNonNull(orderId, "orderId");
      ticketIds = List.copyOf(Objects.requireNonNull(ticketIds, "ticketIds"));
    }
  }

  @Override
  protected Result handle(Command cmd) {
    Instant now = clock.now();
    Reservation reservation = loadReservation(cmd.reservationId());
    requireOwnedByCurrentUser(reservation, cmd.userId());
    requireHoldAndAlive(reservation, now);
    preventDuplicateCheckout(cmd.reservationId());

    List<SeatState> heldSeats = heldSeatsFor(reservation);
    Money computedTotal = sumTotal(heldSeats);
    if (!computedTotal.equals(cmd.expectedTotal())) {
      throw new ConflictException("Total amount changed");
    }

    ScreeningDetail detail =
        catalogQueryRepo
            .findScreeningDetail(reservation.screeningId())
            .orElseThrow(
                () -> new NotFoundException("Screening", reservation.screeningId().value()));
    OrderId orderId = new OrderId(ids.newId());
    Order order =
        new Order(
            orderId,
            cmd.userId(),
            reservation.screeningId(),
            reservation.id(),
            computedTotal,
            PaymentStatus.PENDING,
            OrderStatus.CREATED,
            null,
            null,
            now,
            now,
            0);
    orderRepo.save(order);

    PaymentGateway.PaymentResult paymentResult = paymentGateway.charge(computedTotal);
    paymentRepo.save(
        new Payment(
            new PaymentId(ids.newId()),
            orderId,
            computedTotal,
            PaymentStatus.PAID,
            paymentResult.processedAt(),
            now,
            now,
            0));
    orderRepo.save(order.confirm(now));
    reservationRepo.save(reservation.toConfirmed(now));

    Map<SeatId, TicketId> seatToTicket = new HashMap<>();
    List<TicketId> ticketIds = new ArrayList<>();
    for (SeatState seat : heldSeats) {
      TicketId ticketId = new TicketId(ids.newId());
      ticketRepo.insert(
          new Ticket(
              ticketId,
              orderId,
              reservation.screeningId(),
              detail.screening().movieId(),
              detail.screening().screenId(),
              seat.seatId(),
              cmd.userId(),
              new Money(seat.price(), Currency.JPY),
              TicketStatus.ACTIVE,
              now,
              null,
              null,
              now,
              now,
              0));
      seatToTicket.put(seat.seatId(), ticketId);
      ticketIds.add(ticketId);
    }

    seatStateRepo.markSold(reservation.id(), seatToTicket, now);
    counterRepo.adjust(reservation.screeningId(), 0, -heldSeats.size(), heldSeats.size(), now);
    eventBus.publish(new OrderConfirmedEvent(orderId, cmd.userId(), computedTotal, now));
    eventBus.publish(new TicketsIssuedEvent(orderId, ticketIds, now));

    return new Result(orderId, ticketIds);
  }

  private Reservation loadReservation(ReservationId reservationId) {
    return reservationRepo
        .findById(reservationId)
        .orElseThrow(() -> new NotFoundException("Reservation", reservationId.value()));
  }

  private static void requireOwnedByCurrentUser(Reservation reservation, UserId userId) {
    if (!reservation.userId().equals(userId)) {
      throw new IllegalStateTransitionException(
          "Reservation", "OTHER_USER", "owned by current user");
    }
  }

  private static void requireHoldAndAlive(Reservation reservation, Instant now) {
    if (reservation.status() != ReservationStatus.HOLD) {
      throw new IllegalStateTransitionException(
          "Reservation", reservation.status().name(), "HOLD required");
    }
    if (reservation.expiresAt().isBefore(now)) {
      throw new IllegalStateTransitionException("Reservation", "EXPIRED", "still in HOLD");
    }
  }

  private void preventDuplicateCheckout(ReservationId reservationId) {
    if (orderRepo.findByReservationId(reservationId).isPresent()) {
      throw new ConflictException("Already checked out");
    }
  }

  private List<SeatState> heldSeatsFor(Reservation reservation) {
    List<SeatState> heldSeats =
        seatStateRepo.findByScreening(reservation.screeningId()).stream()
            .filter(seat -> reservation.id().equals(seat.reservationId()))
            .toList();
    if (heldSeats.isEmpty()) {
      throw new ConflictException("Reservation has no held seats");
    }
    return heldSeats;
  }

  private static Money sumTotal(List<SeatState> seats) {
    return seats.stream()
        .map(seat -> new Money(seat.price(), Currency.JPY))
        .reduce(Money.zero(Currency.JPY), Money::plus);
  }
}
