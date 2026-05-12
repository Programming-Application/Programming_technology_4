package com.theater;

import com.theater.catalog.infrastructure.CatalogModule;
import com.theater.identity.application.LoginUseCase;
import com.theater.identity.application.LogoutUseCase;
import com.theater.identity.application.RegisterUserUseCase;
import com.theater.identity.domain.CurrentUserHolder;
import com.theater.identity.domain.PasswordHasher;
import com.theater.identity.domain.UserRepository;
import com.theater.identity.infrastructure.IdentityModule;
import com.theater.ordering.infrastructure.OrderingModule;
import com.theater.reservation.application.ExpireHoldsJob;
import com.theater.reservation.domain.ReservationRepository;
import com.theater.reservation.domain.ScreeningCounterRepository;
import com.theater.reservation.domain.SeatStateRepository;
import com.theater.reservation.infrastructure.ReservationModule;
import com.theater.shared.SharedModule;
import com.theater.shared.bootstrap.DemoDataLoader;
import com.theater.shared.di.Container;
import com.theater.shared.kernel.Clock;
import com.theater.shared.kernel.IdGenerator;
import com.theater.shared.tx.JdbcUnitOfWork;
import com.theater.shared.tx.UnitOfWork;
import com.theater.ticketing.infrastructure.TicketingModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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
  private static ScheduledExecutorService expireHoldsScheduler;
  private static ScheduledFuture<?> expireHoldsTask;

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

    // UseCase (Application 層) の bind は Bootstrap で行う。
    // BC の Module からは Application を参照できない (ArchUnit 制約) ため。
    registerUseCases(container);

    Container.setGlobal(container);
    container.resolve(DemoDataLoader.class).loadIfEmpty();
    startExpireHoldsScheduler(container);
    LOG.info("Application bootstrapped. db={}", dbFile.toAbsolutePath());
  }

  /**
   * UseCase (Application 層) の DI バインディング。
   *
   * <p>各 BC の {@code *Module} は infrastructure 配下なので、ArchUnit の Layered ルール (Application may only
   * be accessed by [UI, Bootstrap]) により UseCase クラスを参照できない。 そのため UseCase の bind は本メソッド (= Bootstrap
   * 層) に集約する。
   */
  private static void registerUseCases(Container container) {
    container.registerSingleton(
        RegisterUserUseCase.class,
        c ->
            new RegisterUserUseCase(
                c.resolve(UnitOfWork.class),
                c.resolve(UserRepository.class),
                c.resolve(PasswordHasher.class),
                c.resolve(Clock.class),
                c.resolve(IdGenerator.class)));
    container.registerSingleton(
        LoginUseCase.class,
        c ->
            new LoginUseCase(
                c.resolve(UnitOfWork.class),
                c.resolve(UserRepository.class),
                c.resolve(PasswordHasher.class),
                c.resolve(CurrentUserHolder.class)));
    container.registerSingleton(
        LogoutUseCase.class, c -> new LogoutUseCase(c.resolve(CurrentUserHolder.class)));
    container.registerSingleton(
        ExpireHoldsJob.class,
        c ->
            new ExpireHoldsJob(
                c.resolve(UnitOfWork.class),
                c.resolve(ReservationRepository.class),
                c.resolve(SeatStateRepository.class),
                c.resolve(ScreeningCounterRepository.class),
                c.resolve(Clock.class)));
  }

  private static void startExpireHoldsScheduler(Container container) {
    if (expireHoldsScheduler != null && !expireHoldsScheduler.isShutdown()) {
      return;
    }
    long intervalMs = reservationExpireHoldsIntervalMs();
    expireHoldsScheduler = Executors.newSingleThreadScheduledExecutor();
    expireHoldsTask =
        expireHoldsScheduler.scheduleAtFixedRate(
            container.resolve(ExpireHoldsJob.class), intervalMs, intervalMs, TimeUnit.MILLISECONDS);
  }

  private static long reservationExpireHoldsIntervalMs() {
    Properties properties = new Properties();
    try (var in = App.class.getResourceAsStream("/application.properties")) {
      if (in != null) {
        properties.load(in);
      }
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Failed to load application.properties", e);
    }
    return Long.parseLong(properties.getProperty("reservation.expireHolds.intervalMs", "1000"));
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

  @Override
  public void stop() {
    if (expireHoldsTask != null) {
      expireHoldsTask.cancel(true);
    }
    if (expireHoldsScheduler != null) {
      expireHoldsScheduler.shutdownNow();
    }
  }
}
