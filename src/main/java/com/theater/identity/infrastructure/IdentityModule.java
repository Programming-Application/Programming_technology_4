package com.theater.identity.infrastructure;

import com.theater.shared.di.Container;
import com.theater.shared.di.Module;

/**
 * identity BC の DI バインディング (skeleton)。
 *
 * <p>ID-01 で {@code UserRepository} の bind を、ID-02/03 で UseCase の bind を追加する。
 */
public final class IdentityModule implements Module {

  @Override
  public void bind(Container container) {
    // TODO(ID-01): UserRepository の JDBC 実装を bind
    // TODO(ID-02): RegisterUserUseCase
    // TODO(ID-03): LoginUseCase / CurrentUserHolder
  }
}
