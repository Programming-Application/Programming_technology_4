package com.theater.ticketing.domain;

/** docs/data_model.md §5 の {@code tickets.status CHECK (...)} に対応。 */
public enum TicketStatus {
  ACTIVE,
  USED,
  CANCELED,
  REFUNDED
}
