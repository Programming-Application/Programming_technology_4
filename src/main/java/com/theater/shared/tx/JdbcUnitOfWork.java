package com.theater.shared.tx;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * JDBC + ThreadLocal による {@link UnitOfWork} 実装。
 *
 * <p>方針:
 *
 * <ul>
 *   <li>Tx 開始時に {@link DataSource} から接続を1本取得し、ThreadLocal に保存。
 *   <li>同一スレッドの Repository は {@link #currentConnection()} 経由で同じ接続を共有。
 *   <li>業務処理が例外を投げたら Rollback。正常終了なら Commit。
 *   <li>{@link Tx#REQUIRED} で既存 Tx があれば join (新規開始しない)。
 * </ul>
 *
 * <p>Read-only トランザクションは <strong>専用の DataSource</strong> から接続を取得する。 これは xerial sqlite-jdbc が {@code
 * Connection.setReadOnly(true)} を接続確立後に呼ぶことを 拒否するため (Cannot change read-only flag after
 * establishing a connection.)。 read-only 用 DS は構築時点で {@code SQLiteConfig.setReadOnly(true)} を持つ 別
 * DS を渡す前提。
 *
 * <p>SQLite の同時書込は単一プロセス前提で WAL + 単一接続で十分。BEGIN IMMEDIATE が必要な パス (HoldSeats など) はリポジトリ側で自前発行する設計。
 */
public final class JdbcUnitOfWork implements UnitOfWork {

  private final DataSource writableDataSource;
  private final DataSource readOnlyDataSource;

  // ErrorProne note: instance ThreadLocal は通常はリークの温床だが、UoW 自体が DI で
  // singleton として保持される前提なので問題ない。テストでは UoW を都度作る。
  @SuppressWarnings("ThreadLocalUsage")
  private final ThreadLocal<Frame> current = new ThreadLocal<>();

  /**
   * @param writableDataSource 通常 (REQUIRED) Tx 用。
   * @param readOnlyDataSource READ_ONLY Tx 用。SQLite なら {@code SQLiteConfig.setReadOnly(true)} で
   *     構築済の DataSource を渡すこと。
   */
  public JdbcUnitOfWork(DataSource writableDataSource, DataSource readOnlyDataSource) {
    this.writableDataSource = Objects.requireNonNull(writableDataSource, "writableDataSource");
    this.readOnlyDataSource = Objects.requireNonNull(readOnlyDataSource, "readOnlyDataSource");
  }

  @Override
  public <R> R execute(Tx mode, Supplier<R> work) {
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(work, "work");

    Frame existing = current.get();
    if (existing != null && mode == Tx.REQUIRED) {
      return work.get();
    }
    if (mode == Tx.REQUIRES_NEW) {
      throw new UnsupportedOperationException("REQUIRES_NEW is not supported yet");
    }

    Connection conn = openConnection(mode);
    Frame frame = new Frame(conn);
    current.set(frame);
    try {
      R result = work.get();
      try {
        conn.commit();
      } catch (SQLException e) {
        safeRollback(conn);
        throw new IllegalStateException("Failed to commit transaction", e);
      }
      return result;
    } catch (RuntimeException e) {
      safeRollback(conn);
      throw e;
    } catch (Error e) {
      safeRollback(conn);
      throw e;
    } finally {
      if (existing == null) {
        current.remove();
      } else {
        current.set(existing);
      }
      closeQuietly(conn);
    }
  }

  @Override
  public Connection currentConnection() {
    Frame f = current.get();
    if (f == null) {
      throw new IllegalStateException("No transaction in progress on this thread");
    }
    return f.connection();
  }

  private Connection openConnection(Tx mode) {
    DataSource ds = (mode == Tx.READ_ONLY) ? readOnlyDataSource : writableDataSource;
    try {
      Connection conn = ds.getConnection();
      conn.setAutoCommit(false);
      // setReadOnly() は呼ばない: SQLite は接続確立後の変更を許可しないため、
      // read-only は DataSource 側 (SQLiteConfig.setReadOnly(true)) で確定済の前提。
      return conn;
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to open JDBC connection", e);
    }
  }

  private static void safeRollback(Connection conn) {
    try {
      conn.rollback();
    } catch (SQLException ignored) {
      // ロールバック自体の失敗は元例外を尊重するため呑む
    }
  }

  private static void closeQuietly(Connection conn) {
    try {
      conn.close();
    } catch (SQLException ignored) {
      // close 失敗は致命的ではない
    }
  }

  /** ThreadLocal に積むフレーム。connection だけ保持すれば十分なので最小化。 */
  private static final class Frame {
    private final Connection connection;

    Frame(Connection connection) {
      this.connection = connection;
    }

    Connection connection() {
      return connection;
    }
  }
}
