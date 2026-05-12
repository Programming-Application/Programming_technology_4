package com.theater.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.reservation.domain.SeatStateRepository;
import com.theater.reservation.domain.SeatStateStatus;
import com.theater.reservation.infrastructure.ReservationModule;
import com.theater.shared.kernel.ScreeningId;
import com.theater.shared.kernel.SeatId;
import com.theater.shared.tx.JdbcUnitOfWork;
import com.theater.shared.tx.Tx;
import com.theater.shared.tx.UnitOfWork;
import com.theater.testkit.Db;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoadSeatMapUseCaseTest {

  static final Instant NOW = Instant.parse("2026-05-04T00:00:00Z");
  static final ScreeningId SCREENING_ID = new ScreeningId("screening-1");

  private Db.TestDb testDb;
  private JdbcUnitOfWork uow;
  private LoadSeatMapUseCase useCase;

  @BeforeEach
  void setup() {
    testDb = Db.openTempFile();
    uow = new JdbcUnitOfWork(testDb.writable(), testDb.readOnly());
    var container = new com.theater.shared.di.Container();
    container.registerSingleton(UnitOfWork.class, c -> uow);
    container.install(new ReservationModule());
    useCase = new LoadSeatMapUseCase(uow, container.resolve(SeatStateRepository.class));
  }

  @AfterEach
  void teardown() {
    testDb.close();
  }

  @Test
  void returns_all_seat_map_entries_ordered_by_seat_id() {
    seedCatalog();
    seedSeatState("A-1", "AVAILABLE", null, 1500);
    seedSeatState("A-2", "AVAILABLE", null, 1500);
    seedSeatState("A-3", "AVAILABLE", null, 2000);

    List<SeatMapEntry> result = useCase.execute(new LoadSeatMapUseCase.Command(SCREENING_ID));

    assertThat(result).hasSize(3);
    assertThat(result.get(0).seatId()).isEqualTo(new SeatId("A-1"));
    assertThat(result.get(0).status()).isEqualTo(SeatStateStatus.AVAILABLE);
    assertThat(result.get(0).price()).isEqualTo(1500);
    assertThat(result.get(2).seatId()).isEqualTo(new SeatId("A-3"));
    assertThat(result.get(2).price()).isEqualTo(2000);
  }

  @Test
  void returns_empty_list_when_no_seats_for_screening() {
    seedCatalog();

    List<SeatMapEntry> result =
        useCase.execute(new LoadSeatMapUseCase.Command(new ScreeningId("no-seats-screening")));

    assertThat(result).isEmpty();
  }

  @Test
  void maps_hold_and_available_statuses_correctly() {
    seedCatalog();
    seedReservation("reservation-1", "user-a");
    seedSeatState("A-1", "HOLD", "reservation-1", 1500);
    seedSeatState("A-2", "AVAILABLE", null, 1500);

    List<SeatMapEntry> result = useCase.execute(new LoadSeatMapUseCase.Command(SCREENING_ID));

    assertThat(result).hasSize(2);
    assertThat(result.stream().filter(e -> e.status() == SeatStateStatus.HOLD).count())
        .isEqualTo(1);
    assertThat(result.stream().filter(e -> e.status() == SeatStateStatus.AVAILABLE).count())
        .isEqualTo(1);
  }

  @Test
  void command_requires_screening_id() {
    assertThatThrownBy(() -> new LoadSeatMapUseCase.Command(null))
        .isInstanceOf(NullPointerException.class);
  }

  private void seedCatalog() {
    uow.executeVoid(
        Tx.REQUIRED,
        () -> {
          Connection conn = uow.currentConnection();
          ReleaseHoldUseCaseTest.insertUser(conn, "user-a");
          ReleaseHoldUseCaseTest.insertCatalog(conn);
        });
  }

  private void seedReservation(String reservationId, String userId) {
    uow.executeVoid(
        Tx.REQUIRED,
        () -> {
          Connection conn = uow.currentConnection();
          ReleaseHoldUseCaseTest.insertReservation(
              conn, reservationId, userId, com.theater.reservation.domain.ReservationStatus.HOLD);
        });
  }

  private void seedSeatState(String seatId, String status, String reservationId, int price) {
    uow.executeVoid(
        Tx.REQUIRED,
        () -> {
          Connection conn = uow.currentConnection();
          try (PreparedStatement ps =
              conn.prepareStatement(
                  """
                  INSERT INTO seat_states(
                    screening_id, seat_id, status, reservation_id, hold_expires_at,
                    ticket_id, price, version, updated_at)
                  VALUES (?,?,?,?,?,?,?,0,?)
                  """)) {
            ps.setString(1, SCREENING_ID.value());
            ps.setString(2, seatId);
            ps.setString(3, status);
            ps.setObject(4, reservationId);
            ps.setObject(5, "HOLD".equals(status) ? NOW.plusSeconds(600).toEpochMilli() : null);
            ps.setObject(6, null);
            ps.setInt(7, price);
            ps.setLong(8, NOW.toEpochMilli());
            ps.executeUpdate();
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }
}
