package com.theater.testkit;

import com.theater.identity.domain.Email;
import com.theater.identity.domain.PasswordHash;
import com.theater.identity.domain.User;
import com.theater.identity.domain.UserRole;
import com.theater.shared.kernel.Clock;
import com.theater.shared.kernel.IdGenerator;
import com.theater.shared.kernel.UserId;
import com.theater.shared.tx.Tx;
import com.theater.shared.tx.UnitOfWork;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;

/**
 * identity BC のテスト seed。
 *
 * <p>seed は Repository を経由せず {@code uow.currentConnection()} 経由で raw SQL を発行する。 これは Repository
 * 自身のテストで seed を使う場面でも循環依存にならないようにするため (B の {@code JdbcCatalogRepositoryIT.seedCatalog} と同じ流儀)。
 */
public record IdentitySeeds(UnitOfWork uow, Clock clock, IdGenerator idGenerator) {

  /** USER ロールで User を作成し DB に永続化、record を返す。テスト内 Tx に乗る (Tx.REQUIRED で join)。 */
  public User user(String email, String name) {
    return user(email, name, UserRole.USER);
  }

  /** ロール指定可。password_hash は placeholder ({@code "test-hash"})。bcrypt の遅さを避けるため。 */
  public User user(String email, String name, UserRole role) {
    Instant now = clock.now();
    User user =
        new User(
            new UserId(idGenerator.newId()),
            new Email(email),
            name,
            new PasswordHash("test-hash"),
            role,
            now,
            now,
            0);
    uow.executeVoid(Tx.REQUIRED, () -> insertUser(uow.currentConnection(), user));
    return user;
  }

  private static void insertUser(Connection conn, User user) {
    try (PreparedStatement ps =
        conn.prepareStatement(
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
      throw new IllegalStateException("Failed to seed user: " + user.id().value(), e);
    }
  }
}
