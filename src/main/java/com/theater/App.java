package com.theater;

import com.theater.shared.di.Container;
import com.theater.shared.kernel.Clock;
import com.theater.shared.kernel.IdGenerator;
import com.theater.shared.tx.JdbcUnitOfWork;
import com.theater.shared.tx.UnitOfWork;
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

    SQLiteConfig config = new SQLiteConfig();
    config.enforceForeignKeys(true);
    config.setJournalMode(SQLiteConfig.JournalMode.WAL);
    config.setBusyTimeout(5_000);
    config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);

    SQLiteDataSource ds = new SQLiteDataSource(config);
    ds.setUrl("jdbc:sqlite:" + dbFile);

    Flyway flyway = Flyway.configure().dataSource(ds).locations("classpath:db/migration").load();
    flyway.migrate();

    Container container = new Container();
    container.registerSingleton(DataSource.class, c -> ds);
    container.registerSingleton(Clock.class, c -> Clock.SYSTEM);
    container.registerSingleton(IdGenerator.class, c -> IdGenerator.UUID_V4);
    container.registerSingleton(
        UnitOfWork.class, c -> new JdbcUnitOfWork(c.resolve(DataSource.class)));

    // TODO(B): container.install(new CatalogModule());
    // TODO(C): container.install(new ReservationModule());
    // TODO(C): container.install(new OrderingModule());
    // TODO(A): container.install(new IdentityModule());
    // TODO(A): container.install(new TicketingModule());

    Container.setGlobal(container);
    LOG.info("Application bootstrapped. db={}", dbFile.toAbsolutePath());
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
