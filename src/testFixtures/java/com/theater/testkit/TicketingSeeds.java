package com.theater.testkit;

import com.theater.shared.kernel.Clock;
import com.theater.shared.kernel.IdGenerator;
import com.theater.shared.tx.UnitOfWork;

/** ticketing BC のテスト seed。TK-01 で {@code ticket(Order, Seat, ...)} ヘルパを追加する。 */
public record TicketingSeeds(UnitOfWork uow, Clock clock, IdGenerator idGenerator) {
  // A が ticketing 用 seed ヘルパを追加。
}
