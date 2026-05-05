package com.theater.shared.kernel;

/** Ticket aggregate identifier. cross-BC 参照あり (seat_states.ticket_id 等)。 */
public record TicketId(String value) implements Identifier {

  public TicketId {
    Identifier.requireNonBlank(value, "ticket id");
  }
}
