package com.theater.testkit;

import com.theater.reservation.domain.Reservation;
import com.theater.reservation.domain.ReservationStatus;
import com.theater.reservation.domain.SeatStateStatus;
import com.theater.shared.kernel.Clock;
import com.theater.shared.kernel.IdGenerator;
import com.theater.shared.kernel.ReservationId;
import com.theater.shared.kernel.ScreeningId;
import com.theater.shared.kernel.SeatId;
import com.theater.shared.kernel.UserId;
import com.theater.shared.tx.Tx;
import com.theater.shared.tx.UnitOfWork;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/** reservation BC のテスト seed。RV-01 の Repository IT / domain unit テストで使う。 */
public record ReservationSeeds(UnitOfWork uow, Clock clock, IdGenerator idGenerator) {

  /** HOLD 状態の Reservation を作成し DB に挿入して返す。 */
  public Reservation holdReservation(UserId userId, ScreeningId screeningId) {
    Instant now = clock.now();
    Instant expiresAt = now.plusSeconds(900);
    Reservation reservation =
        new Reservation(
            new ReservationId(idGenerator.newId()),
            userId,
            screeningId,
            ReservationStatus.HOLD,
            expiresAt,
            now,
            now,
            0L);
    uow.executeVoid(
        Tx.REQUIRED,
        () -> {
          Connection conn = uow.currentConnection();
          insertReservation(conn, reservation);
        });
    return reservation;
  }

  /** 指定座席を AVAILABLE 状態で seat_states に挿入する。 */
  public void seedSeatStatesAvailable(ScreeningId screeningId, List<SeatId> seats, int price) {
    uow.executeVoid(
        Tx.REQUIRED,
        () -> {
          Connection conn = uow.currentConnection();
          Instant now = clock.now();
          for (SeatId seatId : seats) {
            insertSeatState(
                conn, screeningId, seatId, SeatStateStatus.AVAILABLE, null, null, price, now);
          }
        });
  }

  /** 指定座席を HOLD 状態で seat_states に挿入する。 */
  public void seedSeatStatesHold(
      ScreeningId screeningId,
      List<SeatId> seats,
      ReservationId reservationId,
      Instant holdExpiresAt,
      int price) {
    uow.executeVoid(
        Tx.REQUIRED,
        () -> {
          Connection conn = uow.currentConnection();
          Instant now = clock.now();
          for (SeatId seatId : seats) {
            insertSeatState(
                conn,
                screeningId,
                seatId,
                SeatStateStatus.HOLD,
                reservationId.value(),
                holdExpiresAt,
                price,
                now);
          }
        });
  }

  private static void insertReservation(Connection conn, Reservation r) {
    try (PreparedStatement ps =
        conn.prepareStatement(
            """
            INSERT INTO reservations(
              reservation_id, user_id, screening_id, status,
              expires_at, created_at, updated_at, version)
            VALUES (?,?,?,?,?,?,?,?)
            """)) {
      ps.setString(1, r.id().value());
      ps.setString(2, r.userId().value());
      ps.setString(3, r.screeningId().value());
      ps.setString(4, r.status().name());
      ps.setObject(5, r.expiresAt() != null ? r.expiresAt().toEpochMilli() : null);
      ps.setLong(6, r.createdAt().toEpochMilli());
      ps.setLong(7, r.updatedAt().toEpochMilli());
      ps.setLong(8, r.version());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to insert reservation seed", e);
    }
  }

  private static void insertSeatState(
      Connection conn,
      ScreeningId screeningId,
      SeatId seatId,
      SeatStateStatus status,
      String reservationId,
      Instant holdExpiresAt,
      int price,
      Instant updatedAt) {
    try (PreparedStatement ps =
        conn.prepareStatement(
            """
            INSERT INTO seat_states(
              screening_id, seat_id, status, reservation_id,
              hold_expires_at, price, version, updated_at)
            VALUES (?,?,?,?,?,?,0,?)
            """)) {
      ps.setString(1, screeningId.value());
      ps.setString(2, seatId.value());
      ps.setString(3, status.name());
      ps.setObject(4, reservationId);
      ps.setObject(5, holdExpiresAt != null ? holdExpiresAt.toEpochMilli() : null);
      ps.setInt(6, price);
      ps.setLong(7, updatedAt.toEpochMilli());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to insert seat_state seed", e);
    }
  }
}
