package com.theater.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.identity.domain.User;
import com.theater.identity.domain.UserRepository;
import com.theater.identity.domain.UserRole;
import com.theater.identity.infrastructure.BcryptPasswordHasher;
import com.theater.identity.infrastructure.IdentityModule;
import com.theater.shared.di.Container;
import com.theater.shared.error.ConflictException;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link RegisterUserUseCase} の Repository IT。
 *
 * <p>docs/testing.md §2 の Repository IT 区分: 実 SQLite に対して UC を実行し、 application 層の重複検出と
 * 永続化された行が整合することを確認する。bcrypt は cost=4 で差替えて IT 全体の所要時間を抑える。
 */
class RegisterUserUseCaseIT {

  private static final Instant NOW = Instant.parse("2026-05-12T00:00:00Z");

  private Db.TestDb testDb;
  private JdbcUnitOfWork uow;
  private UserRepository repo;
  private RegisterUserUseCase useCase;

  @BeforeEach
  void setup() {
    testDb = Db.openTempFile();
    uow = new JdbcUnitOfWork(testDb.writable(), testDb.readOnly());

    Container container = new Container();
    container.registerSingleton(UnitOfWork.class, c -> uow);
    container.install(new IdentityModule());
    repo = container.resolve(UserRepository.class);

    // bcrypt cost を 4 まで下げて IT の実行時間を抑える (本番は cost=12)。
    useCase =
        new RegisterUserUseCase(
            uow,
            repo,
            new BcryptPasswordHasher(4),
            FixedClock.at(NOW),
            new IncrementingIdGenerator("u-"));
  }

  @AfterEach
  void teardown() {
    testDb.close();
  }

  @Test
  void register_then_find_persists_user() {
    UserId id =
        useCase.execute(new RegisterUserUseCase.Command("alice@example.com", "Alice", "password1"));

    User found = uow.execute(Tx.READ_ONLY, () -> repo.findById(id).orElseThrow());
    assertThat(found.email().value()).isEqualTo("alice@example.com");
    assertThat(found.name()).isEqualTo("Alice");
    assertThat(found.role()).isEqualTo(UserRole.USER);
    assertThat(found.passwordHash().value()).startsWith("$2a$04$");
    assertThat(found.createdAt()).isEqualTo(NOW);
  }

  @Test
  void duplicate_email_throws_conflict_and_leaves_single_row() {
    useCase.execute(new RegisterUserUseCase.Command("dup@example.com", "First", "password1"));

    assertThatThrownBy(
            () ->
                useCase.execute(
                    new RegisterUserUseCase.Command("dup@example.com", "Second", "password2")))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("dup@example.com");

    assertThat(userRowCount("dup@example.com")).isEqualTo(1);
  }

  @Test
  void email_normalization_blocks_case_variant_duplicate() {
    useCase.execute(new RegisterUserUseCase.Command("Mixed@Example.com", "First", "password1"));

    assertThatThrownBy(
            () ->
                useCase.execute(
                    new RegisterUserUseCase.Command("mixed@example.com", "Second", "password2")))
        .isInstanceOf(ConflictException.class);

    assertThat(userRowCount("mixed@example.com")).isEqualTo(1);
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
}
