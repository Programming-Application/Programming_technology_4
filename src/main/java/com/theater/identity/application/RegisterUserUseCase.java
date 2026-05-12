package com.theater.identity.application;

import com.theater.identity.domain.Email;
import com.theater.identity.domain.PasswordHash;
import com.theater.identity.domain.PasswordHasher;
import com.theater.identity.domain.User;
import com.theater.identity.domain.UserRepository;
import com.theater.identity.domain.UserRole;
import com.theater.shared.error.ConflictException;
import com.theater.shared.kernel.Clock;
import com.theater.shared.kernel.IdGenerator;
import com.theater.shared.kernel.UserId;
import com.theater.shared.tx.TransactionalUseCase;
import com.theater.shared.tx.UnitOfWork;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * メールアドレスとパスワードで新規ユーザを登録する Cmd UseCase。
 *
 * <p>多層防御:
 *
 * <ul>
 *   <li>application: {@link UserRepository#findByEmail} で事前検出 → {@link ConflictException}。
 *   <li>infrastructure: {@code users.email UNIQUE} 制約が最終防壁 (race 時)。
 * </ul>
 *
 * <p>パターン: Template Method ({@link TransactionalUseCase}) + Command (record)。
 */
public final class RegisterUserUseCase
    extends TransactionalUseCase<RegisterUserUseCase.Command, UserId> {

  private static final int PASSWORD_MIN_LENGTH = 8;

  private final UserRepository repo;
  private final PasswordHasher hasher;
  private final Clock clock;
  private final IdGenerator ids;

  public RegisterUserUseCase(
      UnitOfWork uow, UserRepository repo, PasswordHasher hasher, Clock clock, IdGenerator ids) {
    super(uow);
    this.repo = Objects.requireNonNull(repo, "repo");
    this.hasher = Objects.requireNonNull(hasher, "hasher");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ids = Objects.requireNonNull(ids, "ids");
  }

  public record Command(String email, String name, String plainPassword) {
    public Command {
      Objects.requireNonNull(email, "email");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(plainPassword, "plainPassword");
    }
  }

  @Override
  protected void validate(Command cmd) {
    if (cmd.name().isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    if (cmd.plainPassword().length() < PASSWORD_MIN_LENGTH) {
      throw new IllegalArgumentException(
          "password must be at least " + PASSWORD_MIN_LENGTH + " characters");
    }
    new Email(normalizeEmail(cmd.email()));
  }

  @Override
  protected UserId handle(Command cmd) {
    Email email = new Email(normalizeEmail(cmd.email()));
    if (repo.findByEmail(email).isPresent()) {
      throw new ConflictException("Email already registered: " + email.value());
    }
    PasswordHash hash = hasher.hash(cmd.plainPassword());
    UserId id = new UserId(ids.newId());
    Instant now = clock.now();
    repo.save(new User(id, email, cmd.name(), hash, UserRole.USER, now, now, 0));
    return id;
  }

  private static String normalizeEmail(String raw) {
    return raw.trim().toLowerCase(Locale.ROOT);
  }
}
