package com.theater.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.reservation.domain.ReservationRepository;
import com.theater.reservation.domain.ScreeningCounterRepository;
import com.theater.reservation.domain.SeatStateRepository;
import com.theater.reservation.domain.SeatStateStatus;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** HoldSeats の Consistency (整合性) テスト。 */
class HoldSeatsConsistencyIT {

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
        () -> {
          HoldSeatsAtomicityTxTest.insertCatalogWithAllAvailable(uow.currentConnection());
          ReleaseHoldUseCaseTest.insertUser(uow.currentConnection(), "user-a");
        });
  }

  @AfterEach
  void teardown() {
    testDb.close();
  }

  @Test
  void after_hold_seats_screenings_counters_match_seat_states_group_by_status() {
    useCase.execute(
        new HoldSeatsUseCase.Command(
            USER_A, SCREENING_ID, List.of(new SeatId("A-1"), new SeatId("A-2"))));

    Map<SeatStateStatus, Long> counts = seatStateCounts();
    assertThat(screeningCounter("available_seat_count"))
        .isEqualTo(counts.getOrDefault(SeatStateStatus.AVAILABLE, 0L).intValue());
    assertThat(screeningCounter("reserved_seat_count"))
        .isEqualTo(counts.getOrDefault(SeatStateStatus.HOLD, 0L).intValue());
    assertThat(screeningCounter("sold_seat_count"))
        .isEqualTo(counts.getOrDefault(SeatStateStatus.SOLD, 0L).intValue());
  }

  @Test
  void counter_invariant_available_plus_reserved_plus_sold_equals_total_seats() {
    useCase.execute(new HoldSeatsUseCase.Command(USER_A, SCREENING_ID, List.of(new SeatId("A-1"))));

    int available = screeningCounter("available_seat_count");
    int reserved = screeningCounter("reserved_seat_count");
    int sold = screeningCounter("sold_seat_count");
    long total = totalSeatStates();

    assertThat((long) (available + reserved + sold)).isEqualTo(total);
  }

  @Test
  void seat_states_check_constraint_rejects_hold_without_reservation_id() {
    assertThatThrownBy(
            () ->
                uow.executeVoid(
                    Tx.REQUIRED,
                    () -> {
                      try (PreparedStatement ps =
                          uow.currentConnection()
                              .prepareStatement(
                                  """
                                  INSERT INTO seat_states(
                                    screening_id, seat_id, status, reservation_id,
                                    hold_expires_at, price, version, updated_at)
                                  VALUES (?,?,'HOLD',NULL,NULL,0,0,?)
                                  """)) {
                        ps.setString(1, SCREENING_ID.value());
                        ps.setString(2, "X-99");
                        ps.setLong(3, NOW.toEpochMilli());
                        ps.executeUpdate();
                      } catch (SQLException e) {
                        throw new IllegalStateException(e);
                      }
                    }))
        .hasCauseInstanceOf(SQLException.class);
  }

  @Test
  void seat_states_check_constraint_rejects_available_with_reservation_id() {
    assertThatThrownBy(
            () ->
                uow.executeVoid(
                    Tx.REQUIRED,
                    () -> {
                      try (PreparedStatement ps =
                          uow.currentConnection()
                              .prepareStatement(
                                  """
                                  INSERT INTO seat_states(
                                    screening_id, seat_id, status, reservation_id,
                                    hold_expires_at, price, version, updated_at)
                                  VALUES (?,?,'AVAILABLE','some-id',NULL,0,0,?)
                                  """)) {
                        ps.setString(1, SCREENING_ID.value());
                        ps.setString(2, "X-98");
                        ps.setLong(3, NOW.toEpochMilli());
                        ps.executeUpdate();
                      } catch (SQLException e) {
                        throw new IllegalStateException(e);
                      }
                    }))
        .hasCauseInstanceOf(SQLException.class);
  }

  private Map<SeatStateStatus, Long> seatStateCounts() {
    return uow.execute(
        Tx.READ_ONLY,
        () -> {
          try (PreparedStatement ps =
              uow.currentConnection()
                  .prepareStatement(
                      "SELECT status, COUNT(*) FROM seat_states"
                          + " WHERE screening_id=? GROUP BY status")) {
            ps.setString(1, SCREENING_ID.value());
            try (ResultSet rs = ps.executeQuery()) {
              var counts = new EnumMap<SeatStateStatus, Long>(SeatStateStatus.class);
              while (rs.next()) {
                counts.put(SeatStateStatus.valueOf(rs.getString(1)), rs.getLong(2));
              }
              return counts;
            }
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  private int screeningCounter(String column) {
    return uow.execute(
        Tx.READ_ONLY,
        () -> {
          try (PreparedStatement ps =
              uow.currentConnection()
                  .prepareStatement("SELECT " + column + " FROM screenings WHERE screening_id=?")) {
            ps.setString(1, SCREENING_ID.value());
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              return rs.getInt(1);
            }
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  private long totalSeatStates() {
    return uow.execute(
        Tx.READ_ONLY,
        () -> {
          try (PreparedStatement ps =
              uow.currentConnection()
                  .prepareStatement("SELECT COUNT(*) FROM seat_states WHERE screening_id=?")) {
            ps.setString(1, SCREENING_ID.value());
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              return rs.getLong(1);
            }
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }
}
