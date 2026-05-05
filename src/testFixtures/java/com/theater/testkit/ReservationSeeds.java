package com.theater.testkit;

import com.theater.shared.kernel.Clock;
import com.theater.shared.kernel.IdGenerator;
import com.theater.shared.tx.UnitOfWork;

/**
 * reservation BC のテスト seed。RV-01 / RV-02 で {@code holdReservation / seatStateAvailable /
 * seatStateHold} 等のヘルパを追加する。
 */
public record ReservationSeeds(UnitOfWork uow, Clock clock, IdGenerator idGenerator) {
  // C が reservation 用 seed ヘルパを追加。
}
