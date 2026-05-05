package com.theater.ticketing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.shared.kernel.Money;
import com.theater.shared.kernel.MovieId;
import com.theater.shared.kernel.OrderId;
import com.theater.shared.kernel.ScreenId;
import com.theater.shared.kernel.ScreeningId;
import com.theater.shared.kernel.SeatId;
import com.theater.shared.kernel.TicketId;
import com.theater.shared.kernel.UserId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TicketTest {

  private static final Instant NOW = Instant.parse("2026-05-04T00:00:00Z");

  @Test
  void active_ticket_constructs_without_used_at() {
    var ticket = active();
    assertThat(ticket.status()).isEqualTo(TicketStatus.ACTIVE);
  }

  @Test
  void used_status_requires_used_at() {
    assertThatThrownBy(
            () ->
                new Ticket(
                    new TicketId("t-1"),
                    new OrderId("o-1"),
                    new ScreeningId("s-1"),
                    new MovieId("m-1"),
                    new ScreenId("sc-1"),
                    new SeatId("A-1"),
                    new UserId("u-1"),
                    Money.jpy(1500),
                    TicketStatus.USED,
                    NOW,
                    null, // usedAt missing → invalid
                    null,
                    NOW,
                    NOW,
                    0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void canceled_status_requires_canceled_at() {
    assertThatThrownBy(
            () ->
                new Ticket(
                    new TicketId("t-1"),
                    new OrderId("o-1"),
                    new ScreeningId("s-1"),
                    new MovieId("m-1"),
                    new ScreenId("sc-1"),
                    new SeatId("A-1"),
                    new UserId("u-1"),
                    Money.jpy(1500),
                    TicketStatus.CANCELED,
                    NOW,
                    null,
                    null, // canceledAt missing → invalid
                    NOW,
                    NOW,
                    0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Ticket active() {
    return new Ticket(
        new TicketId("t-1"),
        new OrderId("o-1"),
        new ScreeningId("s-1"),
        new MovieId("m-1"),
        new ScreenId("sc-1"),
        new SeatId("A-1"),
        new UserId("u-1"),
        Money.jpy(1500),
        TicketStatus.ACTIVE,
        NOW,
        null,
        null,
        NOW,
        NOW,
        0);
  }
}
