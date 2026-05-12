package com.theater.ordering.application.event;

import com.theater.shared.eventbus.DomainEvent;
import com.theater.shared.kernel.Money;
import com.theater.shared.kernel.OrderId;
import com.theater.shared.kernel.UserId;
import java.time.Instant;
import java.util.Objects;

/** OR-05 CancelOrder 完了時に発行するドメインイベント。 */
public record OrderCanceledEvent(
    OrderId orderId, UserId userId, Money refundAmount, Instant occurredAt) implements DomainEvent {

  public OrderCanceledEvent {
    Objects.requireNonNull(orderId, "orderId");
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(refundAmount, "refundAmount");
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
    return "OrderCanceled";
  }

  @Override
  public String payloadJson() {
    return "{\"orderId\":\""
        + orderId.value()
        + "\",\"userId\":\""
        + userId.value()
        + "\",\"refundMinorUnits\":"
        + refundAmount.minorUnits()
        + ",\"currency\":\""
        + refundAmount.currency()
        + "\"}";
  }
}
