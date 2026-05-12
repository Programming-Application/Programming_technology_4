package com.theater.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.identity.domain.Email;
import com.theater.identity.domain.User;
import com.theater.identity.domain.UserRepository;
import com.theater.identity.infrastructure.BcryptPasswordHasher;
import com.theater.identity.infrastructure.IdentityModule;
import com.theater.shared.di.Container;
import com.theater.shared.kernel.UserId;
import com.theater.shared.tx.JdbcUnitOfWork;
import com.theater.shared.tx.Tx;
import com.theater.shared.tx.UnitOfWork;
import com.theater.testkit.Db;
import com.theater.testkit.FixedClock;
import com.theater.testkit.IncrementingIdGenerator;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link RegisterUserUseCase} の Atomicity Tx Test。
 *
 * <p>docs/testing.md §2 Atomicity: {@code repo.save} 直後に例外を注入し、UC を 1 Tx の境界として、 users 行が 永続化されない
 * (= COMMIT されない) ことを確認する。
 */
class RegisterUserAtomicityTxTest {

  private static final Instant NOW = Instant.parse("2026-05-12T00:00:00Z");

  private Db.TestDb testDb;
  private JdbcUnitOfWork uow;
  private UserRepository realRepo;

  @BeforeEach
  void setup() {
    testDb = Db.openTempFile();
    uow = new JdbcUnitOfWork(testDb.writable(), testDb.readOnly());

    Container container = new Container();
    container.registerSingleton(UnitOfWork.class, c -> uow);
    container.install(new IdentityModule());
    realRepo = container.resolve(UserRepository.class);
  }

  @AfterEach
  void teardown() {
    testDb.close();
  }

  @Test
  void exception_after_save_rolls_back_user_row() {
    var failing = new ThrowAfterSaveUserRepository(realRepo);
    var useCase =
        new RegisterUserUseCase(
            uow,
            failing,
            new BcryptPasswordHasher(4),
            FixedClock.at(NOW),
            new IncrementingIdGenerator("u-"));

    assertThatThrownBy(
            () ->
                useCase.execute(
                    new RegisterUserUseCase.Command("rollback@example.com", "Rolly", "password1")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("injected failure after save");

    assertThat(userRowCount("rollback@example.com")).isZero();
  }

  private long userRowCount(String email) {
    return uow.execute(
        Tx.READ_ONLY,
        () -> {
          try (PreparedStatement ps =
              uow.currentConnection()
                  .prepareStatement("SELECT COUNT(*) FROM users WHERE email = ?")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              return rs.getLong(1);
            }
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  /** {@link UserRepository#save} を呼んだ直後に例外を投げる decorator。Atomicity 検証用。 */
  private static final class ThrowAfterSaveUserRepository implements UserRepository {

    private final UserRepository delegate;

    ThrowAfterSaveUserRepository(UserRepository delegate) {
      this.delegate = delegate;
    }

    @Override
    public Optional<User> findById(UserId id) {
      return delegate.findById(id);
    }

    @Override
    public Optional<User> findByEmail(Email email) {
      return delegate.findByEmail(email);
    }

    @Override
    public void save(User user) {
      delegate.save(user);
      throw new IllegalStateException("injected failure after save");
    }
  }
}
