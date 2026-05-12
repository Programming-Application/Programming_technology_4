package com.theater;

import com.theater.catalog.infrastructure.CatalogModule;
import com.theater.identity.infrastructure.IdentityModule;
import com.theater.ordering.infrastructure.OrderingModule;
import com.theater.reservation.infrastructure.ReservationModule;
import com.theater.shared.SharedModule;
import com.theater.shared.bootstrap.DemoDataLoader;
import com.theater.shared.di.Container;
import com.theater.shared.tx.JdbcUnitOfWork;
import com.theater.shared.tx.UnitOfWork;
import com.theater.ticketing.infrastructure.TicketingModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * Application entry point.
 *
 * <p>Owner: Person A. See {@code docs/architecture.md} §7 for the bootstrap sequence.
 */
// pattern: Singleton (JavaFX Application は1プロセス1インスタンス)
public final class App extends Application {

  private static final Logger LOG = LoggerFactory.getLogger(App.class);
  private static final String DB_PATH = "data/theater.db";

  public static void main(String[] args) {
    bootstrap();
    launch(args);
  }

  /** DI 登録 + マイグレーション。テストからも呼び出せるよう public-static で公開。 */
  static void bootstrap() {
    Path dbFile = Paths.get(DB_PATH);
    ensureParent(dbFile);
    String url = "jdbc:sqlite:" + dbFile;

    // 1) writable DS で Flyway を流して DB ファイルとスキーマを実体化させる。
    SQLiteDataSource writable = buildSqliteDataSource(url, false);
    Flyway flyway =
        Flyway.configure().dataSource(writable).locations("classpath:db/migration").load();
    flyway.migrate();

    // 2) read-only DS は DB ファイルが存在してから構築する (xerial sqlite-jdbc は
    //    Connection.setReadOnly() の動的変更を許可しないため、SQLiteConfig 側で確定させる)。
    SQLiteDataSource readOnly = buildSqliteDataSource(url, true);

    Container container = new Container();
    // Connection-level binding は App.bootstrap が直接保有する (両 DataSource の参照を握る必要があるため)。
    container.registerSingleton(DataSource.class, c -> writable); // writable がデフォルト
    container.registerSingleton(UnitOfWork.class, c -> new JdbcUnitOfWork(writable, readOnly));

    // Clock / IdGenerator / DomainEventBus などの shared kernel binding は SharedModule。
    // 各 BC の Repository / UseCase binding は対応する {BC}Module で行われる
    // (中身は ID-* / RV-* / OR-* / TK-* の各 issue で埋められていく)。
    container.install(new SharedModule());
    container.install(new IdentityModule());
    container.install(new CatalogModule());
    container.install(new ReservationModule());
    container.install(new OrderingModule());
    container.install(new TicketingModule());

    Container.setGlobal(container);
    container.resolve(DemoDataLoader.class).loadIfEmpty();
    LOG.info("Application bootstrapped. db={}", dbFile.toAbsolutePath());
  }

  private static SQLiteDataSource buildSqliteDataSource(String url, boolean readOnly) {
    SQLiteConfig config = new SQLiteConfig();
    config.enforceForeignKeys(true);
    config.setJournalMode(SQLiteConfig.JournalMode.WAL);
    config.setBusyTimeout(5_000);
    config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
    if (readOnly) {
      config.setReadOnly(true);
    }
    SQLiteDataSource ds = new SQLiteDataSource(config);
    ds.setUrl(url);
    return ds;
  }

  private static void ensureParent(Path dbFile) {
    Path parent = dbFile.toAbsolutePath().getParent();
    if (parent == null) {
      return;
    }
    try {
      Files.createDirectories(parent);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Failed to create db directory: " + parent, e);
    }
  }

  @Override
  public void start(Stage stage) throws Exception {
    Parent root = FXMLLoader.load(App.class.getResource("/ui/fxml/login.fxml"));
    stage.setTitle("Theater");
    stage.setScene(new Scene(root));
    stage.show();
  }
}
