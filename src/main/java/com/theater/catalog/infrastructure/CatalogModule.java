package com.theater.catalog.infrastructure;

import com.theater.catalog.domain.CatalogQueryRepository;
import com.theater.catalog.domain.ScreeningRepository;
import com.theater.shared.di.Container;
import com.theater.shared.di.Module;
import com.theater.shared.tx.UnitOfWork;

/**
 * catalog BC の DI バインディング。
 *
 * <p>{@link JdbcCatalogRepository} は {@link CatalogQueryRepository} と {@link ScreeningRepository} の
 * **両方** を実装する。Container には qualifier がないため、まず {@code CatalogQueryRepository} に singleton
 * 登録し、{@code ScreeningRepository} にはその同一インスタンスを cast 経由で再 bind する形を取る。
 */
public final class CatalogModule implements Module {

  @Override
  public void bind(Container container) {
    container.registerSingleton(
        CatalogQueryRepository.class, c -> new JdbcCatalogRepository(c.resolve(UnitOfWork.class)));
    container.registerSingleton(
        ScreeningRepository.class,
        c -> (ScreeningRepository) c.resolve(CatalogQueryRepository.class));
  }
}
