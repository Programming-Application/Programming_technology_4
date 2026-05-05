package com.theater.testkit;

import com.theater.shared.kernel.Clock;
import com.theater.shared.kernel.IdGenerator;
import com.theater.shared.tx.UnitOfWork;

/**
 * catalog BC のテスト seed。B が CT-* で {@code movie / screen / screening / seat} を埋める。 既存 {@code
 * JdbcCatalogRepositoryIT.seedCatalog()} はここに移行する候補。
 */
public record CatalogSeeds(UnitOfWork uow, Clock clock, IdGenerator idGenerator) {
  // B が catalog 用 seed ヘルパを追加。
}
