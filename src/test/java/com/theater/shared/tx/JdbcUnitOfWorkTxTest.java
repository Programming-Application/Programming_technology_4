package com.theater.shared.tx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.testkit.Db;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link JdbcUnitOfWork} の Tx 境界テスト。
 *
 * <p>Atomicity / Durability を実 SQLite に対して検証する。docs/testing.md §2 参照。
 */
class JdbcUnitOfWorkTxTest {

    private Db.TestDb testDb;
    private JdbcUnitOfWork uow;

    @BeforeEach
    void setup() {
        testDb = Db.openTempFile();
        uow = new JdbcUnitOfWork(testDb.dataSource());
    }

    @AfterEach
    void teardown() {
        testDb.close();
    }

    @Nested
    class Atomicity {

        @Test
        void commit_persists_writes() {
            uow.execute(
                    Tx.REQUIRED,
                    () -> {
                        insertUser(uow.currentConnection(), "u-1", "a@example.com");
                        return null;
                    });
            assertThat(countUsers()).isEqualTo(1);
        }

        @Test
        void runtime_exception_rolls_back_all_writes() {
            assertThatThrownBy(
                            () ->
                                    uow.execute(
                                            Tx.REQUIRED,
                                            () -> {
                                                insertUser(
                                                        uow.currentConnection(),
                                                        "u-1",
                                                        "a@example.com");
                                                throw new RuntimeException("boom");
                                            }))
                    .hasMessage("boom");
            assertThat(countUsers()).isZero();
        }

        @Test
        void domain_exception_rolls_back_all_writes() {
            assertThatThrownBy(
                            () ->
                                    uow.execute(
                                            Tx.REQUIRED,
                                            () -> {
                                                insertUser(
                                                        uow.currentConnection(),
                                                        "u-1",
                                                        "a@example.com");
                                                insertUser(
                                                        uow.currentConnection(),
                                                        "u-2",
                                                        "b@example.com");
                                                throw new IllegalStateException("invariant");
                                            }))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(countUsers()).isZero();
        }
    }

    @Nested
    class Propagation {

        @Test
        void nested_required_joins_existing_tx() {
            // 内側で例外を投げると外側もまるごと Rollback される (1 Tx として扱われる)
            assertThatThrownBy(
                            () ->
                                    uow.execute(
                                            Tx.REQUIRED,
                                            () -> {
                                                insertUser(
                                                        uow.currentConnection(),
                                                        "outer",
                                                        "outer@x.com");
                                                uow.execute(
                                                        Tx.REQUIRED,
                                                        () -> {
                                                            insertUser(
                                                                    uow.currentConnection(),
                                                                    "inner",
                                                                    "inner@x.com");
                                                            throw new RuntimeException(
                                                                    "fail in inner");
                                                        });
                                                return null;
                                            }))
                    .isInstanceOf(RuntimeException.class);
            assertThat(countUsers()).isZero();
        }

        @Test
        void requires_new_is_not_supported_yet() {
            assertThatThrownBy(() -> uow.execute(Tx.REQUIRES_NEW, () -> null))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    class Durability {

        @Test
        void committed_writes_survive_reconnect() throws SQLException {
            uow.execute(
                    Tx.REQUIRED,
                    () -> {
                        insertUser(uow.currentConnection(), "u-1", "durable@x.com");
                        return null;
                    });

            // 別接続で読み直す
            try (Connection fresh = testDb.dataSource().getConnection();
                    Statement stmt = fresh.createStatement();
                    ResultSet rs =
                            stmt.executeQuery("SELECT email FROM users WHERE user_id='u-1'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("durable@x.com");
            }
        }
    }

    @Test
    void current_connection_throws_outside_tx() {
        assertThatThrownBy(() -> uow.currentConnection()).isInstanceOf(IllegalStateException.class);
    }

    private long countUsers() {
        try (Connection conn = testDb.dataSource().getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM users")) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void insertUser(Connection conn, String id, String email) {
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "INSERT INTO users(user_id,email,name,password_hash,role,created_at,"
                                + "updated_at,version) "
                                + "VALUES (?,?,?,?,?,?,?,0)")) {
            long now = 1_700_000_000_000L;
            ps.setString(1, id);
            ps.setString(2, email);
            ps.setString(3, "name-" + id);
            ps.setString(4, "hash");
            ps.setString(5, "USER");
            ps.setLong(6, now);
            ps.setLong(7, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
