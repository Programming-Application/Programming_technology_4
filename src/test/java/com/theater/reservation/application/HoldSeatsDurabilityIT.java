package com.theater.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.theater.reservation.domain.ReservationRepository;
import com.theater.reservation.domain.ScreeningCounterRepository;
import com.theater.reservation.domain.SeatStateRepository;
import com.theater.reservation.infrastructure.ReservationModule;
import com.theater.shared.kernel.ScreeningId;
import com.theater.shared.kernel.SeatId;
import com.theater.shared.kernel.UserId;
import com.theater.shared.tx.JdbcUnitOfWork;
import com.theater.shared.tx.Tx;
import com.theater.shared.tx.UnitOfWork;
import com.theater.testkit.Db;
import com.theater.testkit.FixedClock;
import com.theater.testkit.IncrementingIdGenerator;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/** HoldSeats の Durability (永続性) テスト。 */
class HoldSeatsDurabilityIT {

  static final Instant NOW = Instant.parse("2026-05-04T00:00:00Z");
  static final ScreeningId SCREENING_ID = new ScreeningId("screening-1");
  static final UserId USER_A = new UserId("user-a");

  private Db.TestDb testDb;
  private JdbcUnitOfWork uow;
  private HoldSeatsUseCase useCase;

  @BeforeEach
  void setup() {
    testDb = Db.openTempFile();
    uow = new JdbcUnitOfWork(testDb.writable(), testDb.readOnly());
    var container = new com.theater.shared.di.Container();
    container.registerSingleton(UnitOfWork.class, c -> uow);
    container.install(new ReservationModule());
    useCase =
        new HoldSeatsUseCase(
            uow,
            container.resolve(SeatStateRepository.class),
            container.resolve(ReservationRepository.class),
            container.resolve(ScreeningCounterRepository.class),
            FixedClock.at(NOW),
            new IncrementingIdGenerator("rid-"),
            HoldSeatsUseCase.DEFAULT_HOLD_DURATION);
    uow.executeVoid(
        Tx.REQUIRED,
        () -> HoldSeatsAtomicityTxTest.insertCatalogWithAllAvailable(uow.currentConnection()));
    uow.executeVoid(
        Tx.REQUIRED, () -> ReleaseHoldUseCaseTest.insertUser(uow.currentConnection(), "user-a"));
  }

  @AfterEach
  void teardown() {
    testDb.close();
  }

  @Test
  void hold_seats_committed_state_survives_new_connection() {
    // (1) HoldSeats 実行 → COMMIT → UoW が接続をクローズ
    HoldSeatsUseCase.Result result =
        useCase.execute(
            new HoldSeatsUseCase.Command(
                USER_A, SCREENING_ID, List.of(new SeatId("A-1"), new SeatId("A-2"))));

    assertThat(result.reservationId()).isNotNull();

    // (2) 同一ファイルへの新規 DataSource (別接続) で読み込む
    JdbcUnitOfWork reconnected = newUow(testDb.file().toString());
    reconnected.execute(
        Tx.READ_ONLY,
        () -> {
          try (PreparedStatement ps =
              reconnected
                  .currentConnection()
                  .prepareStatement(
                      """
                      SELECT status, reservation_id
                        FROM seat_states
                       WHERE screening_id = ?
                         AND seat_id IN ('A-1','A-2')
                      """)) {
            ps.setString(1, SCREENING_ID.value());
            try (ResultSet rs = ps.executeQuery()) {
              int rows = 0;
              while (rs.next()) {
                rows++;
                assertThat(rs.getString("status")).isEqualTo("HOLD");
                assertThat(rs.getString("reservation_id"))
                    .isEqualTo(result.reservationId().value());
              }
              assertThat(rows).isEqualTo(2);
            }
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
          return null;
        });
  }

  @Test
  void reservation_persists_after_reconnect() {
    HoldSeatsUseCase.Result result =
        useCase.execute(
            new HoldSeatsUseCase.Command(USER_A, SCREENING_ID, List.of(new SeatId("A-1"))));

    JdbcUnitOfWork reconnected = newUow(testDb.file().toString());
    reconnected.execute(
        Tx.READ_ONLY,
        () -> {
          try (PreparedStatement ps =
              reconnected
                  .currentConnection()
                  .prepareStatement("SELECT status FROM reservations WHERE reservation_id=?")) {
            ps.setString(1, result.reservationId().value());
            try (ResultSet rs = ps.executeQuery()) {
              assertThat(rs.next()).isTrue();
              assertThat(rs.getString("status")).isEqualTo("HOLD");
            }
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
          return null;
        });
  }

  private static JdbcUnitOfWork newUow(String dbPath) {
    String url = "jdbc:sqlite:" + dbPath;

    SQLiteConfig wCfg = new SQLiteConfig();
    wCfg.enforceForeignKeys(true);
    wCfg.setJournalMode(SQLiteConfig.JournalMode.WAL);
    wCfg.setBusyTimeout(5_000);
    wCfg.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
    wCfg.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
    SQLiteDataSource writable = new SQLiteDataSource(wCfg);
    writable.setUrl(url);

    SQLiteConfig roCfg = new SQLiteConfig();
    roCfg.enforceForeignKeys(true);
    roCfg.setJournalMode(SQLiteConfig.JournalMode.WAL);
    roCfg.setBusyTimeout(5_000);
    roCfg.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
    roCfg.setReadOnly(true);
    SQLiteDataSource readOnly = new SQLiteDataSource(roCfg);
    readOnly.setUrl(url);

    return new JdbcUnitOfWork(writable, readOnly);
  }
}
