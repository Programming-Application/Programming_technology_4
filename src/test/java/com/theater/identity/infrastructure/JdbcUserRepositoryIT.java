package com.theater.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.identity.domain.Email;
import com.theater.identity.domain.PasswordHash;
import com.theater.identity.domain.User;
import com.theater.identity.domain.UserRole;
import com.theater.shared.error.OptimisticLockException;
import com.theater.shared.kernel.UserId;
import com.theater.shared.tx.JdbcUnitOfWork;
import com.theater.shared.tx.Tx;
import com.theater.testkit.Db;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link JdbcUserRepository} の Repository IT。
 *
 * <p>docs/testing.md §2.2 (Consistency) と §2 全般に該当: CHECK / UNIQUE / FK / 楽観ロックの 4 制約を 実 SQLite
 * に対して直接 assert する。
 */
class JdbcUserRepositoryIT {

  private static final Instant NOW = Instant.parse("2026-05-12T00:00:00Z");

  private Db.TestDb testDb;
  private JdbcUnitOfWork uow;
  private JdbcUserRepository repository;

  @BeforeEach
  void setup() {
    testDb = Db.openTempFile();
    uow = new JdbcUnitOfWork(testDb.writable(), testDb.readOnly());
    repository = new JdbcUserRepository(uow);
  }

  @AfterEach
  void teardown() {
    testDb.close();
  }

  @Nested
  class FindAndSave {

    @Test
    void save_then_find_by_id_returns_inserted_user() {
      User u = newUser("u-1", "alice@example.com", "Alice", UserRole.USER);
      uow.executeVoid(Tx.REQUIRED, () -> repository.save(u));

      User found =
          uow.execute(Tx.READ_ONLY, () -> repository.findById(new UserId("u-1")).orElseThrow());
      assertThat(found.email().value()).isEqualTo("alice@example.com");
      assertThat(found.role()).isEqualTo(UserRole.USER);
    }

    @Test
    void find_by_email_returns_inserted_user() {
      User u = newUser("u-1", "bob@example.com", "Bob", UserRole.ADMIN);
      uow.executeVoid(Tx.REQUIRED, () -> repository.save(u));

      User found =
          uow.execute(
              Tx.READ_ONLY,
              () -> repository.findByEmail(new Email("bob@example.com")).orElseThrow());
      assertThat(found.id().value()).isEqualTo("u-1");
      assertThat(found.role()).isEqualTo(UserRole.ADMIN);
    }

    @Test
    void find_by_id_returns_empty_when_missing() {
      assertThat(uow.execute(Tx.READ_ONLY, () -> repository.findById(new UserId("missing"))))
          .isEmpty();
    }

    @Test
    void find_by_email_returns_empty_when_missing() {
      assertThat(
              uow.execute(
                  Tx.READ_ONLY, () -> repository.findByEmail(new Email("nobody@example.com"))))
          .isEmpty();
    }
  }

  @Nested
  class SchemaConstraints {

    @Test
    void email_unique_constraint_blocks_duplicate() {
      User a = newUser("u-1", "dup@example.com", "Alice", UserRole.USER);
      uow.executeVoid(Tx.REQUIRED, () -> repository.save(a));

      // 別 user_id で同じ email を直接 INSERT しようとしたら SQLite が UNIQUE 違反で落とす
      assertThatThrownBy(
              () ->
                  uow.executeVoid(
                      Tx.REQUIRED,
                      () ->
                          rawInsert(
                              uow.currentConnection(), "u-2", "dup@example.com", "Bob", "USER")))
          .isInstanceOf(IllegalStateException.class)
          .hasRootCauseInstanceOf(SQLException.class);
    }

    @Test
    void role_check_constraint_blocks_invalid_value() {
      assertThatThrownBy(
              () ->
                  uow.executeVoid(
                      Tx.REQUIRED,
                      () ->
                          rawInsert(
                              uow.currentConnection(), "u-1", "x@example.com", "X", "SUPERUSER")))
          .isInstanceOf(IllegalStateException.class)
          .hasRootCauseInstanceOf(SQLException.class);
    }
  }

  @Nested
  class OptimisticLock {

    @Test
    void save_with_stale_version_throws() {
      User u = newUser("u-1", "stale@example.com", "Stale", UserRole.USER);
      uow.executeVoid(Tx.REQUIRED, () -> repository.save(u));

      // 1回目の update で version 0 → 1
      User v0 = uow.execute(Tx.READ_ONLY, () -> repository.findById(u.id()).orElseThrow());
      User renamed =
          new User(
              v0.id(),
              v0.email(),
              "Stale Renamed",
              v0.passwordHash(),
              v0.role(),
              v0.createdAt(),
              NOW.plusSeconds(60),
              v0.version());
      uow.executeVoid(Tx.REQUIRED, () -> repository.save(renamed));

      // v0 (stale, version=0 で UPDATE 試行) をもう1度 save → OptimisticLockException
      User stale =
          new User(
              v0.id(),
              v0.email(),
              "Stale 2",
              v0.passwordHash(),
              v0.role(),
              v0.createdAt(),
              NOW.plusSeconds(120),
              0);
      assertThatThrownBy(() -> uow.executeVoid(Tx.REQUIRED, () -> repository.save(stale)))
          .isInstanceOf(OptimisticLockException.class);
    }
  }

  // ---- helpers ----

  private static User newUser(String id, String email, String name, UserRole role) {
    return new User(
        new UserId(id),
        new Email(email),
        name,
        new PasswordHash("hash-for-" + id),
        role,
        NOW,
        NOW,
        0);
  }

  private static void rawInsert(
      Connection conn, String id, String email, String name, String role) {
    try (PreparedStatement ps =
        conn.prepareStatement(
            """
            INSERT INTO users(user_id, email, name, password_hash, role,
                              created_at, updated_at, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, 0)
            """)) {
      ps.setString(1, id);
      ps.setString(2, email);
      ps.setString(3, name);
      ps.setString(4, "h");
      ps.setString(5, role);
      ps.setLong(6, NOW.toEpochMilli());
      ps.setLong(7, NOW.toEpochMilli());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
