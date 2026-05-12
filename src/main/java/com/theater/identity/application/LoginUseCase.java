package com.theater.identity.application;

import com.theater.identity.domain.CurrentUserHolder;
import com.theater.identity.domain.Email;
import com.theater.identity.domain.PasswordHasher;
import com.theater.identity.domain.User;
import com.theater.identity.domain.UserRepository;
import com.theater.shared.error.AuthenticationException;
import com.theater.shared.kernel.UserId;
import com.theater.shared.tx.TransactionalUseCase;
import com.theater.shared.tx.Tx;
import com.theater.shared.tx.UnitOfWork;
import java.util.Locale;
import java.util.Objects;

/**
 * email + 平文パスワードでログインする Query 系 UseCase。
 *
 * <p>{@link UserRepository#findByEmail} 1 発で済むため {@link Tx#READ_ONLY} を使い、書込ロックを取らない。 検証成功時のみ
 * {@link CurrentUserHolder} に User をセットする副作用がある。
 *
 * <p>**情報漏洩防止**: email 不在 / password 不一致のどちらでも同一 {@link AuthenticationException} を投げる。
 *
 * <p>パターン: Template Method ({@link TransactionalUseCase}) + Command (record)。
 */
public final class LoginUseCase
    extends TransactionalUseCase<LoginUseCase.Command, LoginUseCase.Result> {

  private final UserRepository repo;
  private final PasswordHasher hasher;
  private final CurrentUserHolder session;

  public LoginUseCase(
      UnitOfWork uow, UserRepository repo, PasswordHasher hasher, CurrentUserHolder session) {
    super(uow);
    this.repo = Objects.requireNonNull(repo, "repo");
    this.hasher = Objects.requireNonNull(hasher, "hasher");
    this.session = Objects.requireNonNull(session, "session");
  }

  public record Command(String email, String plainPassword) {
    public Command {
      Objects.requireNonNull(email, "email");
      Objects.requireNonNull(plainPassword, "plainPassword");
    }
  }

  public record Result(UserId userId, String displayName) {}

  @Override
  protected Tx txMode() {
    return Tx.READ_ONLY;
  }

  @Override
  protected void validate(Command cmd) {
    if (cmd.email().isBlank()) {
      throw new IllegalArgumentException("email must not be blank");
    }
    if (cmd.plainPassword().isEmpty()) {
      throw new IllegalArgumentException("password must not be empty");
    }
  }

  @Override
  protected Result handle(Command cmd) {
    Email email;
    try {
      email = new Email(normalizeEmail(cmd.email()));
    } catch (IllegalArgumentException e) {
      throw new AuthenticationException();
    }
    User user = repo.findByEmail(email).orElseThrow(AuthenticationException::new);
    if (!hasher.verify(cmd.plainPassword(), user.passwordHash())) {
      throw new AuthenticationException();
    }
    session.setCurrent(user);
    return new Result(user.id(), user.name());
  }

  private static String normalizeEmail(String raw) {
    return raw.trim().toLowerCase(Locale.ROOT);
  }
}
