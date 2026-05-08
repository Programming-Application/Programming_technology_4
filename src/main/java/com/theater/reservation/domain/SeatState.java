package com.theater.reservation.domain;

import com.theater.shared.kernel.ReservationId;
import com.theater.shared.kernel.ScreeningId;
import com.theater.shared.kernel.SeatId;
import com.theater.shared.kernel.TicketId;
import java.time.Instant;
import java.util.Objects;

/** Screening seat state. docs/data_model.md §3 の seat_states CHECK と同じ整合性を持つ。 */
public record SeatState(
    ScreeningId screeningId,
    SeatId seatId,
    SeatStateStatus status,
    ReservationId reservationId,
    Instant holdExpiresAt,
    TicketId ticketId,
    int price,
    long version,
    Instant updatedAt) {

  public SeatState {
    Objects.requireNonNull(screeningId, "screeningId");
    Objects.requireNonNull(seatId, "seatId");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (price < 0) {
      throw new IllegalArgumentException("price must not be negative");
    }
    if (version < 0) {
      throw new IllegalArgumentException("version must not be negative");
    }
    validateStateColumns(status, reservationId, holdExpiresAt, ticketId);
  }

  private static void validateStateColumns(
      SeatStateStatus status,
      ReservationId reservationId,
      Instant holdExpiresAt,
      TicketId ticketId) {
    switch (status) {
      case AVAILABLE -> {
        if (reservationId != null || holdExpiresAt != null || ticketId != null) {
          throw new IllegalArgumentException("AVAILABLE seat must not have related ids");
        }
      }
      case HOLD -> {
        if (reservationId == null || holdExpiresAt == null || ticketId != null) {
          throw new IllegalArgumentException(
              "HOLD seat requires reservationId and holdExpiresAt only");
        }
      }
      case SOLD -> {
        if (reservationId == null || ticketId == null) {
          throw new IllegalArgumentException("SOLD seat requires reservationId and ticketId");
        }
      }
      case BLOCKED -> {
        if (ticketId != null) {
          throw new IllegalArgumentException("BLOCKED seat must not have ticketId");
        }
      }
      default -> throw new IllegalStateException("unsupported seat state status: " + status);
    }
  }
}
