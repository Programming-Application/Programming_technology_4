package com.theater.shared.kernel;

/** Reservation aggregate identifier. cross-BC 参照あり (ordering の checkout 起点など)。 */
public record ReservationId(String value) implements Identifier {

  public ReservationId {
    Identifier.requireNonBlank(value, "reservation id");
  }
}
