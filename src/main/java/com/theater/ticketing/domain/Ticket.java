package com.theater.ticketing.domain;

import com.theater.shared.kernel.Money;
import com.theater.shared.kernel.MovieId;
import com.theater.shared.kernel.OrderId;
import com.theater.shared.kernel.ScreenId;
import com.theater.shared.kernel.ScreeningId;
import com.theater.shared.kernel.SeatId;
import com.theater.shared.kernel.TicketId;
import com.theater.shared.kernel.UserId;
import java.time.Instant;
import java.util.Objects;

/**
 * Ticket 集約。docs/data_model.md §5 の tickets テーブルに対応する domain record。
 *
 * <p>OR-04 Checkout が同 Tx で発行する。{@code uq_tickets_active_seat (screening_id, seat_id) WHERE
 * status='ACTIVE'} がダブルブッキング最終防壁 (TK-01 で migration 化)。
 */
public record Ticket(
    TicketId id,
    OrderId orderId,
    ScreeningId screeningId,
    MovieId movieId,
    ScreenId screenId,
    SeatId seatId,
    UserId userId,
    Money price,
    TicketStatus status,
    Instant purchasedAt,
    Instant usedAt,
    Instant canceledAt,
    Instant createdAt,
    Instant updatedAt,
    long version) {

  public Ticket {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(orderId, "orderId");
    Objects.requireNonNull(screeningId, "screeningId");
    Objects.requireNonNull(movieId, "movieId");
    Objects.requireNonNull(screenId, "screenId");
    Objects.requireNonNull(seatId, "seatId");
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(price, "price");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(purchasedAt, "purchasedAt");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (version < 0) {
      throw new IllegalArgumentException("version must not be negative");
    }
    if (status == TicketStatus.USED && usedAt == null) {
      throw new IllegalArgumentException("USED status requires usedAt");
    }
    if (status == TicketStatus.CANCELED && canceledAt == null) {
      throw new IllegalArgumentException("CANCELED status requires canceledAt");
    }
  }
}
