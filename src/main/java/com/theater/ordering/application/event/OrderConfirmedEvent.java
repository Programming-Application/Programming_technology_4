package com.theater.ordering.application.event;

import com.theater.shared.eventbus.DomainEvent;
import com.theater.shared.kernel.Money;
import com.theater.shared.kernel.OrderId;
import com.theater.shared.kernel.UserId;
import java.time.Instant;
import java.util.Objects;

/** Order confirmed event persisted through the outbox. */
public record OrderConfirmedEvent(OrderId orderId, UserId userId, Money total, Instant occurredAt)
    implements DomainEvent {

  public OrderConfirmedEvent {
    Objects.requireNonNull(orderId, "orderId");
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(total, "total");
    Objects.requireNonNull(occurredAt, "occurredAt");
  }

  @Override
  public String aggregateType() {
    return "Order";
  }

  @Override
  public String aggregateId() {
    return orderId.value();
  }

  @Override
  public String eventType() {
    return "OrderConfirmed";
  }

  @Override
  public String payloadJson() {
    return "{\"orderId\":\""
        + orderId.value()
        + "\",\"userId\":\""
        + userId.value()
        + "\",\"totalMinorUnits\":"
        + total.minorUnits()
        + ",\"currency\":\""
        + total.currency()
        + "\"}";
  }
}
