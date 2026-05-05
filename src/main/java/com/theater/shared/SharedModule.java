package com.theater.shared;

import com.theater.shared.di.Container;
import com.theater.shared.di.Module;
import com.theater.shared.eventbus.DomainEventBus;
import com.theater.shared.eventbus.OutboxDomainEventBus;
import com.theater.shared.kernel.Clock;
import com.theater.shared.kernel.IdGenerator;
import com.theater.shared.tx.UnitOfWork;

/**
 * shared kernel の DI バインディング。
 *
 * <p>{@code App.bootstrap} で最初に install される。{@link UnitOfWork} と {@code DataSource} は connection
 * 情報を持つため App.bootstrap が直接 register しており、本 Module は **その上に乗る抽象** (Clock / IdGenerator /
 * DomainEventBus) のみを担当する。
 */
public final class SharedModule implements Module {

  @Override
  public void bind(Container container) {
    container.registerSingleton(Clock.class, c -> Clock.SYSTEM);
    container.registerSingleton(IdGenerator.class, c -> IdGenerator.UUID_V4);
    container.registerSingleton(
        DomainEventBus.class,
        c -> new OutboxDomainEventBus(c.resolve(UnitOfWork.class), c.resolve(IdGenerator.class)));
  }
}
