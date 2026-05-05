package com.theater.testkit;

import com.theater.shared.kernel.Clock;
import com.theater.shared.kernel.IdGenerator;
import com.theater.shared.tx.UnitOfWork;

/**
 * identity BC のテスト seed。
 *
 * <p>ID-01 で {@code user(Email, name)} などのヘルパを追加する。本ファイルは A 担当 BC のため B / C は触らない
 * (docs/task_split.md §11.1)。
 */
public record IdentitySeeds(UnitOfWork uow, Clock clock, IdGenerator idGenerator) {
  // ID-01 で実装ヘルパを追加。
}
