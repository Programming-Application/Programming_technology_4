package com.theater.ordering.application;

import com.theater.catalog.domain.CatalogQueryRepository;
import com.theater.ordering.application.event.OrderCanceledEvent;
import com.theater.ordering.domain.Order;
import com.theater.ordering.domain.OrderRepository;
import com.theater.ordering.domain.OrderStatus;
import com.theater.ordering.domain.Payment;
import com.theater.ordering.domain.PaymentRepository;
import com.theater.ordering.domain.PaymentStatus;
import com.theater.ordering.domain.Refund;
import com.theater.ordering.domain.RefundRepository;
import com.theater.reservation.domain.Reservation;
import com.theater.reservation.domain.ReservationRepository;
import com.theater.reservation.domain.ReservationStatus;
import com.theater.reservation.domain.ScreeningCounterRepository;
import com.theater.reservation.domain.SeatStateRepository;
import com.theater.shared.error.ConflictException;
import com.theater.shared.error.IllegalStateTransitionException;
import com.theater.shared.error.NotFoundException;
import com.theater.shared.eventbus.DomainEventBus;
import com.theater.shared.kernel.Clock;
import com.theater.shared.kernel.IdGenerator;
import com.theater.shared.kernel.OrderId;
import com.theater.shared.kernel.UserId;
import com.theater.shared.tx.TransactionalUseCase;
import com.theater.shared.tx.UnitOfWork;
import com.theater.ticketing.domain.TicketRepository;
import java.time.Instant;
import java.util.Objects;

/**
 * OR-05 確定済注文をキャンセルし、チケット・座席状態を戻して返金を記録する。
 *
 * <p>Tx 内処理順序: (1) チケット ACTIVE→CANCELED (2) 座席 SOLD→AVAILABLE (3) Refund 挿入 (4) Payment
 * PAID→REFUNDED (5) Order CONFIRMED→CANCELED (6) Reservation CONFIRMED→CANCELED (7) screenings
 * カウンタ調整 (8) OrderCanceled イベント発行。
 */
public final class CancelOrderUseCase
    extends TransactionalUseCase<CancelOrderUseCase.Command, Void> {

  private final OrderRepository orderRepo;
  private final PaymentRepository paymentRepo;
  private final RefundRepository refundRepo;
  private final ReservationRepository reservationRepo;
  private final SeatStateRepository seatStateRepo;
  private final TicketRepository ticketRepo;
  private final ScreeningCounterRepository counterRepo;
  private final CatalogQueryRepository catalogQueryRepo;
  private final DomainEventBus eventBus;
  private final Clock clock;
  private final IdGenerator ids;

  public record Repositories(
      OrderRepository orderRepo,
      PaymentRepository paymentRepo,
      RefundRepository refundRepo,
      ReservationRepository reservationRepo,
      SeatStateRepository seatStateRepo,
      TicketRepository ticketRepo,
      ScreeningCounterRepository counterRepo,
      CatalogQueryRepository catalogQueryRepo) {
    public Repositories {
      Objects.requireNonNull(orderRepo, "orderRepo");
      Objects.requireNonNull(paymentRepo, "paymentRepo");
      Objects.requireNonNull(refundRepo, "refundRepo");
      Objects.requireNonNull(reservationRepo, "reservationRepo");
      Objects.requireNonNull(seatStateRepo, "seatStateRepo");
      Objects.requireNonNull(ticketRepo, "ticketRepo");
      Objects.requireNonNull(counterRepo, "counterRepo");
      Objects.requireNonNull(catalogQueryRepo, "catalogQueryRepo");
    }
  }

  public CancelOrderUseCase(
      UnitOfWork uow, Repositories repos, DomainEventBus eventBus, Clock clock, IdGenerator ids) {
    super(uow);
    Objects.requireNonNull(repos, "repos");
    this.orderRepo = repos.orderRepo();
    this.paymentRepo = repos.paymentRepo();
    this.refundRepo = repos.refundRepo();
    this.reservationRepo = repos.reservationRepo();
    this.seatStateRepo = repos.seatStateRepo();
    this.ticketRepo = repos.ticketRepo();
    this.counterRepo = repos.counterRepo();
    this.catalogQueryRepo = repos.catalogQueryRepo();
    this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ids = Objects.requireNonNull(ids, "ids");
  }

  public record Command(OrderId orderId, UserId userId) {
    public Command {
      Objects.requireNonNull(orderId, "orderId");
      Objects.requireNonNull(userId, "userId");
    }
  }

  @Override
  protected Void handle(Command cmd) {
    Instant now = clock.now();

    Order order =
        orderRepo
            .findById(cmd.orderId())
            .orElseThrow(() -> new NotFoundException("Order", cmd.orderId().value()));

    if (!order.userId().equals(cmd.userId())) {
      throw new IllegalStateTransitionException("Order", "OTHER_USER", "owned by current user");
    }
    if (order.orderStatus() != OrderStatus.CONFIRMED) {
      throw new IllegalStateTransitionException(
          "Order", order.orderStatus().name(), OrderStatus.CANCELED.name());
    }
    if (refundRepo.findByOrderId(cmd.orderId()).isPresent()) {
      throw new ConflictException("Order already refunded: " + cmd.orderId().value());
    }

    var detail =
        catalogQueryRepo
            .findScreeningDetail(order.screeningId())
            .orElseThrow(() -> new NotFoundException("Screening", order.screeningId().value()));
    if (!detail.screening().startTime().isAfter(now)) {
      throw new IllegalStateTransitionException(
          "Order", "AFTER_SCREENING_START", "before screening start");
    }

    Reservation reservation =
        reservationRepo
            .findById(order.reservationId())
            .orElseThrow(() -> new NotFoundException("Reservation", order.reservationId().value()));

    Payment payment =
        paymentRepo
            .findByOrderId(cmd.orderId())
            .orElseThrow(() -> new NotFoundException("Payment", cmd.orderId().value()));

    // (1) チケット ACTIVE → CANCELED
    int canceledTickets = ticketRepo.cancelByOrderId(cmd.orderId(), now);

    // (2) 座席 SOLD → AVAILABLE
    int releasedSeats = seatStateRepo.releaseSoldByReservation(order.reservationId(), now);

    // (3) Refund 記録 (order_id UNIQUE で二重返金を DB レベルでも防止)
    refundRepo.save(
        new Refund(ids.newId(), cmd.orderId(), order.totalAmount(), "Order canceled", now));

    // (4) Payment PAID → REFUNDED
    paymentRepo.save(
        new Payment(
            payment.id(),
            payment.orderId(),
            payment.amount(),
            PaymentStatus.REFUNDED,
            payment.processedAt(),
            payment.createdAt(),
            now,
            payment.version() + 1));

    // (5) Order CONFIRMED → CANCELED
    orderRepo.save(order.cancel(now));

    // (6) Reservation CONFIRMED → CANCELED
    if (reservation.status() == ReservationStatus.CONFIRMED) {
      reservationRepo.save(reservation.toCanceled(now));
    }

    // (7) screenings カウンタ調整 (sold -= n, available += n)
    int n = Math.max(canceledTickets, releasedSeats);
    counterRepo.adjust(order.screeningId(), n, 0, -n, now);

    // (8) イベント発行
    eventBus.publish(new OrderCanceledEvent(cmd.orderId(), cmd.userId(), order.totalAmount(), now));

    return null;
  }
}
