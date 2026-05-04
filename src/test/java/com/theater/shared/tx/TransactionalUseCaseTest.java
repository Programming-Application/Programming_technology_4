package com.theater.shared.tx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.testkit.Db;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link TransactionalUseCase} の契約テスト。
 *
 * <p>UoW 単体の rollback 挙動は {@link JdbcUnitOfWorkTxTest} で見ているが、こちらは <em>TransactionalUseCase
 * の抽象を介した経路でも同じ保証が成立すること</em>を直接 assert する。 抽象を1段挟むたびに contract test を1つ持つことで、リファクタで {@code
 * execute} の テンプレ順序が壊れたときに気付ける。
 */
class TransactionalUseCaseTest {

  private Db.TestDb testDb;
  private JdbcUnitOfWork uow;

  @BeforeEach
  void setup() {
    testDb = Db.openTempFile();
    uow = new JdbcUnitOfWork(testDb.writable(), testDb.readOnly());
  }

  @AfterEach
  void teardown() {
    testDb.close();
  }

  @Nested
  class TemplateOrdering {

    @Test
    void execute_runs_validate_then_handle_inside_tx() {
      var uc = new EchoUseCase(uow);
      String result = uc.execute(new EchoCommand("hi"));
      assertThat(result).isEqualTo("HI");
    }

    @Test
    void validate_failure_skips_handle() {
      var uc = new EchoUseCase(uow);
      assertThatThrownBy(() -> uc.execute(new EchoCommand("")))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void null_command_is_rejected() {
      var uc = new EchoUseCase(uow);
      assertThatThrownBy(() -> uc.execute(null)).isInstanceOf(NullPointerException.class);
    }
  }

  /**
   * docs/testing.md §2.1 (Atomicity) の「抽象側」での確認。 handle が中途まで書込んだ後に例外を投げると、 TransactionalUseCase
   * 経由でも全 Rollback されることを assert する。
   */
  @Nested
  class AtomicityContract {

    @Test
    void handle_failure_rolls_back_writes_made_before_throw() {
      var uc = new WriteThenFailUseCase(uow);

      assertThatThrownBy(() -> uc.execute("u-1"))
          .isInstanceOf(RuntimeException.class)
          .hasMessage("after write");

      // 中途まで書いた users 行は Rollback されている
      assertThat(countUsers()).isZero();
    }

    @Test
    void domain_exception_in_handle_also_rolls_back() {
      var uc = new WriteThenThrowDomainUseCase(uow);

      assertThatThrownBy(() -> uc.execute("u-1")).isInstanceOf(IllegalStateException.class);

      assertThat(countUsers()).isZero();
    }
  }

  /**
   * validate は Tx の<em>外</em>で動くという契約。失敗時には connection も開かれず、 DB に副作用が出ない。
   *
   * <p>handle で書込もうとする UseCase に対し validate を空文字で reject させ、 users が0行であることで 「handle に到達していない = Tx
   * も実質開いていない」を確認する。
   */
  @Nested
  class ValidatePreTxContract {

    @Test
    void validate_failure_does_not_touch_db() {
      var uc = new ValidateThenWriteUseCase(uow);

      assertThatThrownBy(() -> uc.execute("")).isInstanceOf(IllegalArgumentException.class);

      assertThat(countUsers()).isZero();
    }
  }

  // ---------- helpers ----------

  private long countUsers() {
    try (Connection conn = testDb.writable().getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM users")) {
      rs.next();
      return rs.getLong(1);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void insertUser(Connection conn, String userId) {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "INSERT INTO users(user_id,email,name,password_hash,role,created_at,"
                + "updated_at,version) "
                + "VALUES (?,?,?,?,?,?,?,0)")) {
      long now = 1_700_000_000_000L;
      ps.setString(1, userId);
      ps.setString(2, userId + "@example.com");
      ps.setString(3, "name-" + userId);
      ps.setString(4, "hash");
      ps.setString(5, "USER");
      ps.setLong(6, now);
      ps.setLong(7, now);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  // ---------- UseCase fixtures ----------

  private record EchoCommand(String input) {}

  /** 正常系・validate のチェック用。DB は触らない。 */
  private static final class EchoUseCase extends TransactionalUseCase<EchoCommand, String> {
    EchoUseCase(UnitOfWork uow) {
      super(uow);
    }

    @Override
    protected void validate(EchoCommand command) {
      if (command.input().isEmpty()) {
        throw new IllegalArgumentException("input must not be blank");
      }
    }

    @Override
    protected String handle(EchoCommand command) {
      // currentConnection() を呼べることで Tx の中にいることを示す
      assertThat(uow.currentConnection()).isNotNull();
      return command.input().toUpperCase(Locale.ROOT);
    }
  }

  /** users INSERT のあと RuntimeException を投げる。Rollback の確認用。 */
  private static final class WriteThenFailUseCase extends TransactionalUseCase<String, Void> {
    WriteThenFailUseCase(UnitOfWork uow) {
      super(uow);
    }

    @Override
    protected Void handle(String userId) {
      insertUser(uow.currentConnection(), userId);
      throw new RuntimeException("after write");
    }
  }

  /** 同上だがドメイン由来の例外 (IllegalStateException) を投げる。 */
  private static final class WriteThenThrowDomainUseCase
      extends TransactionalUseCase<String, Void> {
    WriteThenThrowDomainUseCase(UnitOfWork uow) {
      super(uow);
    }

    @Override
    protected Void handle(String userId) {
      insertUser(uow.currentConnection(), userId);
      throw new IllegalStateException("invariant broken");
    }
  }

  /** validate で空文字を reject。validate 失敗時に handle (= write) が呼ばれない確認用。 */
  private static final class ValidateThenWriteUseCase extends TransactionalUseCase<String, Void> {
    ValidateThenWriteUseCase(UnitOfWork uow) {
      super(uow);
    }

    @Override
    protected void validate(String input) {
      if (input.isEmpty()) {
        throw new IllegalArgumentException("input must not be blank");
      }
    }

    @Override
    protected Void handle(String userId) {
      insertUser(uow.currentConnection(), userId);
      return null;
    }
  }
}
