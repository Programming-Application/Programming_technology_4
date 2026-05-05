package com.theater.testkit;

import com.theater.shared.kernel.Clock;
import com.theater.shared.kernel.IdGenerator;
import com.theater.shared.tx.UnitOfWork;

/** ordering BC のテスト seed。OR-01 / OR-04 で {@code order / payment / refund} 用ヘルパを追加する。 */
public record OrderingSeeds(UnitOfWork uow, Clock clock, IdGenerator idGenerator) {
  // C が ordering 用 seed ヘルパを追加。
}
