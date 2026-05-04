package com.theater.shared.kernel;

/**
 * Seat identifier (例: "A-10")。物理座席ID。{@link ScreenId} とのペアで一意。
 *
 * <p>Cross-BC 参照あり: catalog (master) / reservation (SeatState) / ticketing (Ticket)。
 */
public record SeatId(String value) implements Identifier {

  public SeatId {
    Identifier.requireNonBlank(value, "seat id");
  }
}
