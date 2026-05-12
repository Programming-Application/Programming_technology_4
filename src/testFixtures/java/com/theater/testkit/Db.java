package com.theater.testkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * テスト用の DB ヘルパー。
 *
 * <ul>
 *   <li>{@link #openTempFile()}: 一時ファイル SQLite を新規作成し Flyway で migrate 済の状態で返す。 {@link
 *       TestDb#close()} でファイルと WAL/SHM を消す。
 *   <li>{@link #snapshot(DataSource)}: 全テーブルの行数を取り、Atomicity テストで「変化が無いこと」 を assert するのに使う。
 * </ul>
 *
 * <p>writable / readOnly の 2 DS を返すのは、xerial sqlite-jdbc が {@code Connection.setReadOnly(true)}
 * を接続確立後に拒否するため。read-only Tx は構築時点で {@code SQLiteConfig.setReadOnly(true)} が立っている専用 DataSource
 * からのみ取れる。
 */
public final class Db {

  private Db() {}

  /**
   * 一時ファイルに新規 SQLite を生成し、Flyway で全マイグレを適用して返す。
   *
   * <p>SQLite の {@code :memory:} は接続ごとに別 DB になり Flyway とリポジトリで状態が分かれる。 これを避けるため必ずファイル DB を使う。
   */
  public static TestDb openTempFile() {
    try {
      Path file = Files.createTempFile("theater-test-", ".db");
      // SQLite に新規作成させたいので一旦消す (Files.createTempFile は0byte ファイルを作る)
      Files.deleteIfExists(file);

      String url = "jdbc:sqlite:" + file;

      // 1) writable DS で Flyway を流して DB ファイルとスキーマを実体化させる。
      SQLiteDataSource writable = buildDataSource(url, false);
      Flyway.configure().dataSource(writable).locations("classpath:db/migration").load().migrate();

      // 2) read-only DS は Flyway 後に作る (SQLITE_OPEN_READONLY は対象ファイル不在で open 失敗するため)。
      SQLiteDataSource readOnly = buildDataSource(url, true);

      return new TestDb(writable, readOnly, file);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to create temp SQLite database", e);
    }
  }

  private static SQLiteDataSource buildDataSource(String url, boolean readOnly) {
    SQLiteConfig config = new SQLiteConfig();
    config.enforceForeignKeys(true);
    config.setJournalMode(SQLiteConfig.JournalMode.WAL);
    config.setBusyTimeout(5_000);
    config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
    if (readOnly) {
      config.setReadOnly(true);
    } else {
      // WAL モードで DEFERRED→WRITER 昇格時に発生する SQLITE_BUSY_SNAPSHOT を防ぐため、
      // 書込用 DS は BEGIN IMMEDIATE でライトロックをトランザクション開始時に取得する。
      config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
    }
    SQLiteDataSource ds = new SQLiteDataSource(config);
    ds.setUrl(url);
    return ds;
  }

  /** 全テーブルの行数を取って Atomicity 比較用のスナップショットを作る。 */
  public static Map<String, Long> snapshot(DataSource ds) {
    Map<String, Long> counts = new LinkedHashMap<>();
    try (Connection conn = ds.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs =
            stmt.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' "
                    + "AND name NOT LIKE 'sqlite_%' "
                    + "AND name NOT LIKE 'flyway_%' "
                    + "ORDER BY name")) {
      while (rs.next()) {
        String table = rs.getString(1);
        counts.put(table, countTable(conn, table));
      }
      return counts;
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to snapshot DB", e);
    }
  }

  private static long countTable(Connection conn, String table) throws SQLException {
    // table 名はメタデータから取得しているので SQL injection の余地はない
    try (Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM \"" + table + "\"")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  /**
   * 一時 DB と関連ファイルを保持し、close で掃除する。
   *
   * @param writable 通常 (REQUIRED) Tx 用の DataSource。
   * @param readOnly READ_ONLY Tx 用の DataSource ({@code SQLiteConfig.setReadOnly(true)} 済)。
   * @param file 一時 .db ファイルのパス。close 時に WAL/SHM とまとめて削除される。
   */
  public record TestDb(DataSource writable, DataSource readOnly, Path file)
      implements AutoCloseable {

    @Override
    public void close() {
      deleteIfExists(file);
      deleteIfExists(Paths.get(file + "-wal"));
      deleteIfExists(Paths.get(file + "-shm"));
    }

    private static void deleteIfExists(Path p) {
      try {
        Files.deleteIfExists(p);
      } catch (IOException ignored) {
        // テスト終了時の掃除失敗は致命的ではない
      }
    }
  }
}
