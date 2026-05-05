package com.theater.testkit;

import com.theater.shared.kernel.Clock;
import com.theater.shared.kernel.IdGenerator;
import com.theater.shared.tx.UnitOfWork;
import java.util.Objects;

/**
 * BC 別 seed クラスの aggregator。
 *
 * <p>テストコードからは {@code seeds.identity.user(...)} / {@code seeds.catalog.movie(...)} のように navigate
 * して使う。各 BC の seed ヘルパは {@link IdentitySeeds} 等それぞれ別ファイルに分かれており、 各 BC issue が独立して編集できる
 * (docs/task_split.md §11.1)。
 *
 * <p>本クラス自体は **PLAT-02 が作成して以降変更しない** 契約。新しい BC 別 seed フィールドが必要になった場合のみ touch する。
 */
public final class Seeds {

  public final IdentitySeeds identity;
  public final CatalogSeeds catalog;
  public final ReservationSeeds reservation;
  public final OrderingSeeds ordering;
  public final TicketingSeeds ticketing;

  public Seeds(UnitOfWork uow, Clock clock, IdGenerator idGenerator) {
    Objects.requireNonNull(uow, "uow");
    Objects.requireNonNull(clock, "clock");
    Objects.requireNonNull(idGenerator, "idGenerator");
    this.identity = new IdentitySeeds(uow, clock, idGenerator);
    this.catalog = new CatalogSeeds(uow, clock, idGenerator);
    this.reservation = new ReservationSeeds(uow, clock, idGenerator);
    this.ordering = new OrderingSeeds(uow, clock, idGenerator);
    this.ticketing = new TicketingSeeds(uow, clock, idGenerator);
  }
}
