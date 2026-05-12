package com.theater.identity.domain;

import com.theater.shared.kernel.UserId;
import java.util.Objects;
import java.util.Optional;

/**
 * ログイン中のユーザを保持するセッション。本案件は single-user / single-process 前提だが、 JavaFX Application Thread と 背景タスク
 * (ExpireHoldsJob 等) から同時参照される可能性に備え {@code volatile} で公開。
 *
 * <p>パターン: Singleton ({@code IdentityModule} で唯一インスタンスを bind)。
 */
public final class CurrentUserHolder {

  private volatile User current;

  public void setCurrent(User user) {
    this.current = Objects.requireNonNull(user, "user");
  }

  public Optional<User> current() {
    return Optional.ofNullable(current);
  }

  public void clear() {
    this.current = null;
  }

  /** ログイン中であることを前提に UserId を取り出す。未ログイン時は {@link IllegalStateException}。 */
  public UserId requireUserId() {
    return current().map(User::id).orElseThrow(() -> new IllegalStateException("Not logged in"));
  }
}
