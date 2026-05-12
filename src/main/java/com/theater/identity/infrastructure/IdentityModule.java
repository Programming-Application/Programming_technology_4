package com.theater.identity.infrastructure;

import com.theater.identity.domain.PasswordHasher;
import com.theater.identity.domain.UserRepository;
import com.theater.shared.di.Container;
import com.theater.shared.di.Module;
import com.theater.shared.tx.UnitOfWork;

/**
 * identity BC の DI バインディング。
 *
 * <p>ID-01 で {@link UserRepository} と {@link PasswordHasher} を bind。 ID-03 で {@code
 * CurrentUserHolder} が追加される。UseCase の bind は ArchUnit 制約 (Infrastructure → Application を 禁ずる)
 * を満たすため Bootstrap 層 ({@code App.bootstrap}) で行う。
 */
public final class IdentityModule implements Module {

  @Override
  public void bind(Container container) {
    container.registerSingleton(
        UserRepository.class, c -> new JdbcUserRepository(c.resolve(UnitOfWork.class)));
    container.registerSingleton(PasswordHasher.class, c -> new BcryptPasswordHasher());
    // TODO(ID-03): CurrentUserHolder
  }
}
