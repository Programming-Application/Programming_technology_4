package com.theater.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.identity.domain.Email;
import com.theater.identity.domain.PasswordHash;
import com.theater.identity.domain.PasswordHasher;
import com.theater.identity.domain.User;
import com.theater.identity.domain.UserRepository;
import com.theater.identity.domain.UserRole;
import com.theater.shared.error.ConflictException;
import com.theater.shared.kernel.UserId;
import com.theater.shared.tx.Tx;
import com.theater.shared.tx.UnitOfWork;
import com.theater.testkit.FixedClock;
import com.theater.testkit.IncrementingIdGenerator;
import java.sql.Connection;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link RegisterUserUseCase} の Unit Test。
 *
 * <p>docs/testing.md §2 の Unit Test 区分: 純粋 application ロジック (validate / ConflictException) を 軽量
 * fake の組合せで検証する。Repository IT で実 SQLite に対する UNIQUE 制約を確認する役割は分離。
 */
class RegisterUserUseCaseTest {

  private static final Instant NOW = Instant.parse("2026-05-12T00:00:00Z");

  private RegisterUserUseCase newUseCase(InMemoryUserRepository repo) {
    return new RegisterUserUseCase(
        new NoOpUnitOfWork(),
        repo,
        new PrefixPasswordHasher(),
        FixedClock.at(NOW),
        new IncrementingIdGenerator("u-"));
  }

  @Nested
  class Validate {

    @Test
    void blank_name_rejected() {
      var uc = newUseCase(new InMemoryUserRepository());
      assertThatThrownBy(
              () -> uc.execute(new RegisterUserUseCase.Command("a@example.com", "  ", "password1")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("name");
    }

    @Test
    void short_password_rejected() {
      var uc = newUseCase(new InMemoryUserRepository());
      assertThatThrownBy(
              () -> uc.execute(new RegisterUserUseCase.Command("a@example.com", "Alice", "short")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("password");
    }

    @Test
    void invalid_email_format_rejected() {
      var uc = newUseCase(new InMemoryUserRepository());
      assertThatThrownBy(
              () ->
                  uc.execute(new RegisterUserUseCase.Command("not-an-email", "Alice", "password1")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("email");
    }

    @Test
    void null_field_rejected_at_command_construction() {
      assertThatThrownBy(() -> new RegisterUserUseCase.Command(null, "Alice", "password1"))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> new RegisterUserUseCase.Command("a@example.com", null, "password1"))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> new RegisterUserUseCase.Command("a@example.com", "Alice", null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  class Handle {

    @Test
    void happy_path_saves_user_with_hashed_password() {
      InMemoryUserRepository repo = new InMemoryUserRepository();
      var uc = newUseCase(repo);

      UserId id =
          uc.execute(new RegisterUserUseCase.Command("Alice@Example.com", "Alice", "password1"));

      assertThat(id.value()).isEqualTo("u-1");
      User saved = repo.findById(id).orElseThrow();
      assertThat(saved.email().value()).isEqualTo("alice@example.com");
      assertThat(saved.name()).isEqualTo("Alice");
      assertThat(saved.role()).isEqualTo(UserRole.USER);
      assertThat(saved.passwordHash().value()).isEqualTo("hashed:password1");
      assertThat(saved.createdAt()).isEqualTo(NOW);
      assertThat(saved.updatedAt()).isEqualTo(NOW);
      assertThat(saved.version()).isZero();
    }

    @Test
    void duplicate_email_throws_conflict_exception() {
      InMemoryUserRepository repo = new InMemoryUserRepository();
      var uc = newUseCase(repo);
      uc.execute(new RegisterUserUseCase.Command("dup@example.com", "First", "password1"));

      assertThatThrownBy(
              () ->
                  uc.execute(
                      new RegisterUserUseCase.Command("dup@example.com", "Second", "password2")))
          .isInstanceOf(ConflictException.class)
          .hasMessageContaining("dup@example.com");
      assertThat(repo.users).hasSize(1);
    }

    @Test
    void email_normalized_before_duplicate_check() {
      InMemoryUserRepository repo = new InMemoryUserRepository();
      var uc = newUseCase(repo);
      uc.execute(new RegisterUserUseCase.Command("  Mixed@Case.COM ", "First", "password1"));

      assertThatThrownBy(
              () ->
                  uc.execute(
                      new RegisterUserUseCase.Command("mixed@case.com", "Second", "password2")))
          .isInstanceOf(ConflictException.class);
    }
  }

  // ---- fakes ----

  private static final class InMemoryUserRepository implements UserRepository {
    final Map<UserId, User> users = new LinkedHashMap<>();
    final Map<String, UserId> byEmail = new HashMap<>();

    @Override
    public Optional<User> findById(UserId id) {
      return Optional.ofNullable(users.get(id));
    }

    @Override
    public Optional<User> findByEmail(Email email) {
      UserId id = byEmail.get(email.value());
      return id == null ? Optional.empty() : Optional.ofNullable(users.get(id));
    }

    @Override
    public void save(User user) {
      users.put(user.id(), user);
      byEmail.put(user.email().value(), user.id());
    }
  }

  private static final class PrefixPasswordHasher implements PasswordHasher {
    @Override
    public PasswordHash hash(String plain) {
      Objects.requireNonNull(plain, "plain");
      return new PasswordHash("hashed:" + plain);
    }

    @Override
    public boolean verify(String plain, PasswordHash hash) {
      return hash.value().equals("hashed:" + plain);
    }
  }

  /** Tx 境界を持たない fake UnitOfWork。Unit Test では Tx 内容には興味がないので work をそのまま実行する。 */
  private static final class NoOpUnitOfWork implements UnitOfWork {
    @Override
    public <R> R execute(Tx mode, Supplier<R> work) {
      return work.get();
    }

    @Override
    public Connection currentConnection() {
      throw new IllegalStateException("currentConnection is unused in unit test");
    }
  }
}
