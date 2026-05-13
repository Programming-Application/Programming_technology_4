package com.theater.ticketing.application;

import com.theater.shared.kernel.Money;
import com.theater.shared.kernel.OrderId;
import com.theater.shared.kernel.TicketId;
import com.theater.ticketing.domain.Ticket;
import com.theater.ticketing.domain.TicketStatus;
import java.time.Instant;

/**
 * チケット詳細 (`GetTicketDetailUseCase`) の view DTO。Summary の上位互換情報を持つ。
 *
 * <p>{@code seatLabel} は {@code SeatId.value()} (例: "A1") をそのまま用いる。
 */
public record TicketDetailView(
    TicketId ticketId,
    OrderId orderId,
    String movieTitle,
    String screenName,
    Instant screeningStartTime,
    Instant screeningEndTime,
    String seatLabel,
    Money price,
    TicketStatus status,
    Instant purchasedAt,
    Instant usedAt) {

  public static TicketDetailView from(
      Ticket ticket,
      String movieTitle,
      String screenName,
      Instant screeningStartTime,
      Instant screeningEndTime) {
    return new TicketDetailView(
        ticket.id(),
        ticket.orderId(),
        movieTitle,
        screenName,
        screeningStartTime,
        screeningEndTime,
        ticket.seatId().value(),
        ticket.price(),
        ticket.status(),
        ticket.purchasedAt(),
        ticket.usedAt());
  }
}
