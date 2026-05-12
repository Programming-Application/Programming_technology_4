package com.theater.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.identity.domain.CurrentUserHolder;
import com.theater.identity.domain.Email;
import com.theater.identity.domain.PasswordHash;
import com.theater.identity.domain.PasswordHasher;
import com.theater.identity.domain.User;
import com.theater.identity.domain.UserRepository;
import com.theater.identity.domain.UserRole;
import com.theater.shared.error.AuthenticationException;
import com.theater.shared.kernel.UserId;
import com.theater.shared.tx.Tx;
import com.theater.shared.tx.UnitOfWork;
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
 * {@link LoginUseCase} および {@link LogoutUseCase} の Unit Test。
 *
 * <p>純粋 application ロジック (email 正規化 / 認証失敗時の例外統一 / セッション副作用) を fake で検証する。 bcrypt の実 round-trip は
 * {@code JdbcUserRepositoryIT} 側で確認済 (ID-01)。
 */
class LoginUseCaseTest {

  private static final Instant NOW = Instant.parse("2026-05-12T00:00:00Z");
  private static final UserId ALICE_ID = new UserId("u-1");

  private static User alice(PasswordHash hash) {
    return new User(
        ALICE_ID, new Email("alice@example.com"), "Alice", hash, UserRole.USER, NOW, NOW, 0);
  }

  private LoginUseCase newUseCase(InMemoryUserRepository repo, CurrentUserHolder session) {
    return new LoginUseCase(new NoOpUnitOfWork(), repo, new PrefixPasswordHasher(), session);
  }

  @Nested
  class Handle {

    @Test
    void happy_path_sets_current_user_and_returns_id() {
      InMemoryUserRepository repo = new InMemoryUserRepository();
      repo.save(alice(new PasswordHash("hashed:password1")));
      CurrentUserHolder session = new CurrentUserHolder();
      LoginUseCase uc = newUseCase(repo, session);

      LoginUseCase.Result result =
          uc.execute(new LoginUseCase.Command("alice@example.com", "password1"));

      assertThat(result.userId()).isEqualTo(ALICE_ID);
      assertThat(result.displayName()).isEqualTo("Alice");
      assertThat(session.current()).isPresent();
      assertThat(session.current().orElseThrow().id()).isEqualTo(ALICE_ID);
      assertThat(session.requireUserId()).isEqualTo(ALICE_ID);
    }

    @Test
    void email_is_normalized_before_lookup() {
      InMemoryUserRepository repo = new InMemoryUserRepository();
      repo.save(alice(new PasswordHash("hashed:password1")));
      CurrentUserHolder session = new CurrentUserHolder();
      LoginUseCase uc = newUseCase(repo, session);

      LoginUseCase.Result result =
          uc.execute(new LoginUseCase.Command("  Alice@EXAMPLE.com ", "password1"));

      assertThat(result.userId()).isEqualTo(ALICE_ID);
    }

    @Test
    void unknown_email_throws_authentication_exception_without_touching_session() {
      InMemoryUserRepository repo = new InMemoryUserRepository();
      CurrentUserHolder session = new CurrentUserHolder();
      LoginUseCase uc = newUseCase(repo, session);

      assertThatThrownBy(
              () -> uc.execute(new LoginUseCase.Command("ghost@example.com", "password1")))
          .isInstanceOf(AuthenticationException.class)
          .hasMessage("Invalid credentials");
      assertThat(session.current()).isEmpty();
    }

    @Test
    void wrong_password_throws_authentication_exception_without_touching_session() {
      InMemoryUserRepository repo = new InMemoryUserRepository();
      repo.save(alice(new PasswordHash("hashed:password1")));
      CurrentUserHolder session = new CurrentUserHolder();
      LoginUseCase uc = newUseCase(repo, session);

      assertThatThrownBy(
              () -> uc.execute(new LoginUseCase.Command("alice@example.com", "wrong-pass")))
          .isInstanceOf(AuthenticationException.class)
          .hasMessage("Invalid credentials");
      assertThat(session.current()).isEmpty();
    }

    @Test
    void malformed_email_is_treated_as_authentication_failure_not_validation() {
      // RegisterUser と違い Login は外部入力に対し情報漏洩しないため、フォーマット異常も同じ Authentication 例外に集約する。
      InMemoryUserRepository repo = new InMemoryUserRepository();
      CurrentUserHolder session = new CurrentUserHolder();
      LoginUseCase uc = newUseCase(repo, session);

      assertThatThrownBy(() -> uc.execute(new LoginUseCase.Command("not-an-email", "password1")))
          .isInstanceOf(AuthenticationException.class);
      assertThat(session.current()).isEmpty();
    }
  }

  @Nested
  class Validate {

    @Test
    void blank_email_rejected_before_repository_lookup() {
      InMemoryUserRepository repo = new InMemoryUserRepository();
      LoginUseCase uc = newUseCase(repo, new CurrentUserHolder());

      assertThatThrownBy(() -> uc.execute(new LoginUseCase.Command("   ", "password1")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("email");
      assertThat(repo.lookupCount).isZero();
    }

    @Test
    void empty_password_rejected_before_repository_lookup() {
      InMemoryUserRepository repo = new InMemoryUserRepository();
      LoginUseCase uc = newUseCase(repo, new CurrentUserHolder());

      assertThatThrownBy(() -> uc.execute(new LoginUseCase.Command("alice@example.com", "")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("password");
      assertThat(repo.lookupCount).isZero();
    }

    @Test
    void null_command_field_rejected_at_command_construction() {
      assertThatThrownBy(() -> new LoginUseCase.Command(null, "password1"))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> new LoginUseCase.Command("alice@example.com", null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  class Logout {

    @Test
    void logout_clears_session_after_login() {
      InMemoryUserRepository repo = new InMemoryUserRepository();
      repo.save(alice(new PasswordHash("hashed:password1")));
      CurrentUserHolder session = new CurrentUserHolder();
      LoginUseCase login = newUseCase(repo, session);
      LogoutUseCase logout = new LogoutUseCase(session);

      login.execute(new LoginUseCase.Command("alice@example.com", "password1"));
      assertThat(session.current()).isPresent();

      logout.execute();
      assertThat(session.current()).isEmpty();
      assertThatThrownBy(session::requireUserId).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void logout_is_idempotent_when_no_user_is_logged_in() {
      CurrentUserHolder session = new CurrentUserHolder();
      LogoutUseCase logout = new LogoutUseCase(session);

      logout.execute();
      logout.execute();

      assertThat(session.current()).isEmpty();
    }
  }

  @Nested
  class TxMode {

    @Test
    void login_runs_in_read_only_tx() {
      InMemoryUserRepository repo = new InMemoryUserRepository();
      repo.save(alice(new PasswordHash("hashed:password1")));
      CurrentUserHolder session = new CurrentUserHolder();
      RecordingUnitOfWork recording = new RecordingUnitOfWork();
      LoginUseCase uc = new LoginUseCase(recording, repo, new PrefixPasswordHasher(), session);

      uc.execute(new LoginUseCase.Command("alice@example.com", "password1"));

      assertThat(recording.lastTx).isEqualTo(Tx.READ_ONLY);
    }
  }

  // ---- fakes ----

  private static final class InMemoryUserRepository implements UserRepository {
    final Map<UserId, User> users = new LinkedHashMap<>();
    final Map<String, UserId> byEmail = new HashMap<>();
    int lookupCount = 0;

    @Override
    public Optional<User> findById(UserId id) {
      return Optional.ofNullable(users.get(id));
    }

    @Override
    public Optional<User> findByEmail(Email email) {
      lookupCount++;
      UserId id = byEmail.get(email.value());
      return id == null ? Optional.empty() : Optional.ofNullable(users.get(id));
    }

    @Override
    public void save(User user) {
      users.put(user.id(), user);
      byEmail.put(user.email().value(), user.id());
    }
  }

  /** {@code "hashed:" + plain} を hash とみなす単純な fake。bcrypt は infrastructure テストで検証する。 */
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

  /** どの {@link Tx} で呼ばれたかを記録するだけの fake。 */
  private static final class RecordingUnitOfWork implements UnitOfWork {
    Tx lastTx;

    @Override
    public <R> R execute(Tx mode, Supplier<R> work) {
      this.lastTx = mode;
      return work.get();
    }

    @Override
    public Connection currentConnection() {
      throw new IllegalStateException("currentConnection is unused in unit test");
    }
  }
}
