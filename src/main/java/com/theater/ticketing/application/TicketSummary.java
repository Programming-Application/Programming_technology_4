package com.theater.ticketing.application;

import com.theater.shared.kernel.TicketId;
import com.theater.ticketing.domain.Ticket;
import com.theater.ticketing.domain.TicketStatus;
import java.time.Instant;

/**
 * チケット一覧 (`ListMyTicketsUseCase`) の 1 件を表す内部 view DTO。
 *
 * <p>catalog から取得した movie title / screen name / screening startTime を平坦に展開して保持する。 `seatLabel` は
 * {@code SeatId.value()} (例: "A1") をそのまま用いる (catalog 側に findSeatById が無く、 本 issue のスコープ外であるため
 * interface 追加を避けた)。
 */
public record TicketSummary(
    TicketId ticketId,
    String movieTitle,
    String screenName,
    Instant screeningStartTime,
    String seatLabel,
    TicketStatus status) {

  public static TicketSummary from(
      Ticket ticket, String movieTitle, String screenName, Instant screeningStartTime) {
    return new TicketSummary(
        ticket.id(),
        movieTitle,
        screenName,
        screeningStartTime,
        ticket.seatId().value(),
        ticket.status());
  }
}
