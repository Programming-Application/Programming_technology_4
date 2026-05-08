package com.theater.reservation.domain;

/** Per-seat reservation state for a screening. */
public enum SeatStateStatus {
  AVAILABLE,
  HOLD,
  SOLD,
  BLOCKED
}
