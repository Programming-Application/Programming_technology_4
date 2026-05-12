package com.theater.identity.infrastructure;

import com.theater.identity.domain.PasswordHasher;
import com.theater.identity.domain.UserRepository;
import com.theater.shared.di.Container;
import com.theater.shared.di.Module;
import com.theater.shared.tx.UnitOfWork;

/**
 * identity BC の DI バインディング。
 *
 * <p>ID-01 で {@link UserRepository} と {@link PasswordHasher} を bind。 ID-02 / ID-03 で UseCase +
 * {@code CurrentUserHolder} の bind が追加される。
 */
public final class IdentityModule implements Module {

  @Override
  public void bind(Container container) {
    container.registerSingleton(
        UserRepository.class, c -> new JdbcUserRepository(c.resolve(UnitOfWork.class)));
    container.registerSingleton(PasswordHasher.class, c -> new BcryptPasswordHasher());
    // TODO(ID-02): RegisterUserUseCase
    // TODO(ID-03): LoginUseCase / CurrentUserHolder
  }
}
