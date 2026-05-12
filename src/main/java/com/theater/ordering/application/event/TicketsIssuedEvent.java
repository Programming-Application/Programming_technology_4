package com.theater.ordering.application.event;

import com.theater.shared.eventbus.DomainEvent;
import com.theater.shared.kernel.OrderId;
import com.theater.shared.kernel.TicketId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Tickets issued event persisted through the outbox. */
public record TicketsIssuedEvent(OrderId orderId, List<TicketId> ticketIds, Instant occurredAt)
    implements DomainEvent {

  public TicketsIssuedEvent {
    Objects.requireNonNull(orderId, "orderId");
    ticketIds = List.copyOf(Objects.requireNonNull(ticketIds, "ticketIds"));
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
    return "TicketsIssued";
  }

  @Override
  public String payloadJson() {
    String ids =
        ticketIds.stream()
            .map(ticketId -> "\"" + ticketId.value() + "\"")
            .collect(Collectors.joining(","));
    return "{\"orderId\":\"" + orderId.value() + "\",\"ticketIds\":[" + ids + "]}";
  }
}
