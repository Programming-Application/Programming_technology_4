package com.theater.shared.kernel;

/**
 * Screening (上映会) identifier.
 *
 * <p>Cross-BC 参照あり: catalog (master) / reservation (Reservation.screeningId /
 * SeatState.screeningId) / ordering (Order.screeningId) / ticketing (Ticket.screeningId)。
 */
public record ScreeningId(String value) implements Identifier {

  public ScreeningId {
    Identifier.requireNonBlank(value, "screening id");
  }
}
