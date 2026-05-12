package com.theater.shared.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.theater.identity.domain.PasswordHash;
import com.theater.identity.infrastructure.BcryptPasswordHasher;
import com.theater.shared.kernel.Clock;
import com.theater.shared.tx.JdbcUnitOfWork;
import com.theater.shared.tx.Tx;
import com.theater.testkit.Db;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class DemoDataLoaderTest {

  private static final Instant NOW = Instant.parse("2026-05-12T00:00:00Z");

  private Db.TestDb testDb;
  private JdbcUnitOfWork uow;
  private DemoDataLoader loader;

  @BeforeEach
  void setup() {
    testDb = Db.openTempFile();
    uow = new JdbcUnitOfWork(testDb.writable(), testDb.readOnly());
    loader = new DemoDataLoader(uow, new FixedClock());
  }

  @AfterEach
  void teardown() {
    testDb.close();
  }

  @Test
  void temp_database_starts_without_demo_data() {
    assertThat(countRows("movies")).isZero();
    assertThat(countRows("users")).isZero();
  }

  @Test
  void loadIfEmpty_inserts_demo_catalog_identity_and_seat_states_once() {
    loader.loadIfEmpty();
    loader.loadIfEmpty();

    assertThat(countRows("users")).isEqualTo(2);
    assertThat(countRows("movies")).isEqualTo(3);
    assertThat(countRows("screens")).isEqualTo(1);
    assertThat(countRows("seats")).isEqualTo(50);
    assertThat(countRows("screenings")).isEqualTo(3);
    assertThat(countRows("seat_states")).isEqualTo(150);
    assertThat(countRows("seat_states WHERE status = 'AVAILABLE'")).isEqualTo(150);
    assertThat(countRows("screenings WHERE status = 'OPEN'")).isEqualTo(3);
  }

  @Test
  void loadIfEmpty_creates_screenings_in_the_next_week() {
    loader.loadIfEmpty();

    long outsideWindow =
        countRows(
            "screenings WHERE start_time < "
                + NOW.toEpochMilli()
                + " OR start_time >= "
                + NOW.plusSeconds(7 * 24 * 60 * 60).toEpochMilli());

    assertThat(outsideWindow).isZero();
  }

  @Test
  void loadIfEmpty_uses_known_password_for_demo_users() {
    loader.loadIfEmpty();

    String passwordHash = readString("SELECT password_hash FROM users WHERE user_id = 'demo-user'");

    assertThat(new BcryptPasswordHasher().verify("password123", new PasswordHash(passwordHash)))
        .isTrue();
  }

  private long countRows(String tableExpression) {
    return uow.execute(Tx.READ_ONLY, () -> countRows(uow.currentConnection(), tableExpression));
  }

  private String readString(String sql) {
    return uow.execute(Tx.READ_ONLY, () -> readString(uow.currentConnection(), sql));
  }

  private static long countRows(Connection conn, String tableExpression) {
    try (Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + tableExpression)) {
      rs.next();
      return rs.getLong(1);
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to count " + tableExpression, e);
    }
  }

  private static String readString(Connection conn, String sql) {
    try (Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      rs.next();
      return rs.getString(1);
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to read string", e);
    }
  }

  private static final class FixedClock implements Clock {
    @Override
    public Instant now() {
      return NOW;
    }
  }
}
