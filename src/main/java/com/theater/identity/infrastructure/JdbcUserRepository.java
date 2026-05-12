package com.theater.identity.infrastructure;

import com.theater.identity.domain.Email;
import com.theater.identity.domain.PasswordHash;
import com.theater.identity.domain.User;
import com.theater.identity.domain.UserRepository;
import com.theater.identity.domain.UserRole;
import com.theater.shared.error.OptimisticLockException;
import com.theater.shared.kernel.UserId;
import com.theater.shared.tx.UnitOfWork;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link UserRepository} の JDBC 実装。
 *
 * <p>{@link UnitOfWork#currentConnection()} 経由で現 Tx の Connection を取得。Tx 外で呼ぶと {@link
 * IllegalStateException}。
 *
 * <p>{@link #save} は {@code findById} で存在判定して INSERT / UPDATE を切替。UPDATE は楽観ロック ({@code WHERE
 * version = ?}) で衝突時 {@link OptimisticLockException}。
 */
final class JdbcUserRepository implements UserRepository {

  private final UnitOfWork uow;

  JdbcUserRepository(UnitOfWork uow) {
    this.uow = Objects.requireNonNull(uow, "uow");
  }

  @Override
  public Optional<User> findById(UserId id) {
    try (PreparedStatement ps =
        connection()
            .prepareStatement(
                """
                SELECT user_id, email, name, password_hash, role,
                       created_at, updated_at, version
                  FROM users
                 WHERE user_id = ?
                """)) {
      ps.setString(1, id.value());
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(toUser(rs)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to find user: " + id.value(), e);
    }
  }

  @Override
  public Optional<User> findByEmail(Email email) {
    try (PreparedStatement ps =
        connection()
            .prepareStatement(
                """
                SELECT user_id, email, name, password_hash, role,
                       created_at, updated_at, version
                  FROM users
                 WHERE email = ?
                """)) {
      ps.setString(1, email.value());
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(toUser(rs)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to find user by email: " + email.value(), e);
    }
  }

  @Override
  public void save(User user) {
    Objects.requireNonNull(user, "user");
    if (findById(user.id()).isPresent()) {
      update(user);
    } else {
      insert(user);
    }
  }

  private void insert(User user) {
    try (PreparedStatement ps =
        connection()
            .prepareStatement(
                """
                INSERT INTO users(user_id, email, name, password_hash, role,
                                  created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
      ps.setString(1, user.id().value());
      ps.setString(2, user.email().value());
      ps.setString(3, user.name());
      ps.setString(4, user.passwordHash().value());
      ps.setString(5, user.role().name());
      ps.setLong(6, user.createdAt().toEpochMilli());
      ps.setLong(7, user.updatedAt().toEpochMilli());
      ps.setLong(8, user.version());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to insert user: " + user.id().value(), e);
    }
  }

  private void update(User user) {
    try (PreparedStatement ps =
        connection()
            .prepareStatement(
                """
                UPDATE users
                   SET email = ?,
                       name = ?,
                       password_hash = ?,
                       role = ?,
                       updated_at = ?,
                       version = version + 1
                 WHERE user_id = ?
                   AND version = ?
                """)) {
      ps.setString(1, user.email().value());
      ps.setString(2, user.name());
      ps.setString(3, user.passwordHash().value());
      ps.setString(4, user.role().name());
      ps.setLong(5, user.updatedAt().toEpochMilli());
      ps.setString(6, user.id().value());
      ps.setLong(7, user.version());
      int updated = ps.executeUpdate();
      if (updated != 1) {
        throw new OptimisticLockException("User", user.id().value());
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to update user: " + user.id().value(), e);
    }
  }

  private Connection connection() {
    return uow.currentConnection();
  }

  private static User toUser(ResultSet rs) throws SQLException {
    return new User(
        new UserId(rs.getString("user_id")),
        new Email(rs.getString("email")),
        rs.getString("name"),
        new PasswordHash(rs.getString("password_hash")),
        UserRole.valueOf(rs.getString("role")),
        Instant.ofEpochMilli(rs.getLong("created_at")),
        Instant.ofEpochMilli(rs.getLong("updated_at")),
        rs.getLong("version"));
  }
}
