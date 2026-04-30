package com.theater.shared;

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
import org.junit.jupiter.api.Test;

/**
 * V001 マイグレーションが期待どおりにテーブル/制約を作るかの検証 (Repository テストの枠)。
 *
 * <p>docs/testing.md §2.2 (Consistency) に該当する。CHECK / UNIQUE / FK が CI で確実に 確認されるように。
 */
class SchemaMigrationIT {

    private Db.TestDb testDb;

    @BeforeEach
    void setup() {
        testDb = Db.openTempFile();
    }

    @AfterEach
    void teardown() {
        testDb.close();
    }

    @Test
    void users_table_is_created_with_unique_email() {
        try (Connection conn = testDb.dataSource().getConnection()) {
            insertUser(conn, "u-1", "a@example.com");
            assertThatThrownBy(() -> insertUser(conn, "u-2", "a@example.com"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("UNIQUE");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void users_role_check_constraint_blocks_invalid_roles() {
        try (Connection conn = testDb.dataSource().getConnection();
                PreparedStatement ps =
                        conn.prepareStatement(
                                "INSERT INTO users(user_id,email,name,password_hash,role,"
                                        + "created_at,updated_at,version) "
                                        + "VALUES (?,?,?,?,?,?,?,0)")) {
            ps.setString(1, "u-1");
            ps.setString(2, "a@x.com");
            ps.setString(3, "n");
            ps.setString(4, "h");
            ps.setString(5, "ROOT"); // 不正な role
            ps.setLong(6, 1L);
            ps.setLong(7, 1L);
            assertThatThrownBy(ps::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("CHECK");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void outbox_table_is_created() {
        try (Connection conn = testDb.dataSource().getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs =
                        stmt.executeQuery(
                                "SELECT name FROM sqlite_master "
                                        + "WHERE type='table' "
                                        + "AND name='domain_events_outbox'")) {
            assertThat(rs.next()).isTrue();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void snapshot_initially_empty() {
        var snapshot = Db.snapshot(testDb.dataSource());
        assertThat(snapshot).containsEntry("users", 0L).containsEntry("domain_events_outbox", 0L);
    }

    private static void insertUser(Connection conn, String id, String email) throws SQLException {
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "INSERT INTO users(user_id,email,name,password_hash,role,created_at,"
                                + "updated_at,version) "
                                + "VALUES (?,?,?,?,?,?,?,0)")) {
            ps.setString(1, id);
            ps.setString(2, email);
            ps.setString(3, "n");
            ps.setString(4, "h");
            ps.setString(5, "USER");
            ps.setLong(6, 1L);
            ps.setLong(7, 1L);
            ps.executeUpdate();
        }
    }
}
