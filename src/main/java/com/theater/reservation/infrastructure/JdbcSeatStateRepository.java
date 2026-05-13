package com.theater.reservation.infrastructure;

import com.theater.reservation.domain.SeatState;
import com.theater.reservation.domain.SeatStateRepository;
import com.theater.reservation.domain.SeatStateStatus;
import com.theater.shared.kernel.ReservationId;
import com.theater.shared.kernel.ScreeningId;
import com.theater.shared.kernel.SeatId;
import com.theater.shared.kernel.TicketId;
import com.theater.shared.tx.UnitOfWork;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** JDBC implementation for screening seat states. */
final class JdbcSeatStateRepository implements SeatStateRepository {

  private final UnitOfWork uow;

  JdbcSeatStateRepository(UnitOfWork uow) {
    this.uow = Objects.requireNonNull(uow, "uow");
  }

  @Override
  public List<SeatState> findByScreening(ScreeningId screeningId) {
    try (PreparedStatement ps =
        connection()
            .prepareStatement(
                """
                SELECT screening_id, seat_id, status, reservation_id, hold_expires_at,
                       ticket_id, price, version, updated_at
                  FROM seat_states
                 WHERE screening_id = ?
                 ORDER BY seat_id
                """)) {
      ps.setString(1, screeningId.value());
      List<SeatState> seats = new ArrayList<>();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          seats.add(toSeatState(rs));
        }
      }
      return seats;
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to find seat states: " + screeningId.value(), e);
    }
  }

  @Override
  public int tryHold(
      ScreeningId screeningId,
      List<SeatId> seats,
      ReservationId reservationId,
      Instant expiresAt,
      Instant now) {
    Objects.requireNonNull(seats, "seats");
    int updated = 0;
    for (SeatId seat : seats) {
      updated += holdOne(screeningId, seat, reservationId, expiresAt, now);
    }
    return updated;
  }

  @Override
  public int releaseSoldByReservation(ReservationId reservationId, Instant now) {
    try (PreparedStatement ps =
        connection()
            .prepareStatement(
                """
                UPDATE seat_states
                   SET status = 'AVAILABLE',
                       reservation_id = NULL,
                       hold_expires_at = NULL,
                       ticket_id = NULL,
                       version = version + 1,
                       updated_at = ?
                 WHERE reservation_id = ?
                   AND status = 'SOLD'
                """)) {
      ps.setLong(1, toMillis(now));
      ps.setString(2, reservationId.value());
      return ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to release sold seats: " + reservationId.value(), e);
    }
  }

  @Override
  public int releaseByReservation(ReservationId reservationId, Instant now) {
    try (PreparedStatement ps =
        connection()
            .prepareStatement(
                """
                UPDATE seat_states
                   SET status = 'AVAILABLE',
                       reservation_id = NULL,
                       hold_expires_at = NULL,
                       version = version + 1,
                       updated_at = ?
                 WHERE reservation_id = ?
                   AND status = 'HOLD'
                """)) {
      ps.setLong(1, toMillis(now));
      ps.setString(2, reservationId.value());
      return ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to release seats: " + reservationId.value(), e);
    }
  }

  @Override
  public void markSold(
      ReservationId reservationId, Map<SeatId, TicketId> seatToTicket, Instant now) {
    Objects.requireNonNull(seatToTicket, "seatToTicket");
    for (var entry : seatToTicket.entrySet()) {
      markSoldOne(reservationId, entry.getKey(), entry.getValue(), now);
    }
  }

  @Override
  public void markExpired(List<ReservationId> reservationIds, Instant now) {
    Objects.requireNonNull(reservationIds, "reservationIds");
    Objects.requireNonNull(now, "now");
    for (ReservationId id : reservationIds) {
      releaseByReservation(id, now);
    }
  }

  private int holdOne(
      ScreeningId screeningId,
      SeatId seat,
      ReservationId reservationId,
      Instant expiresAt,
      Instant now) {
    try (PreparedStatement ps =
        connection()
            .prepareStatement(
                """
                UPDATE seat_states
                   SET status = 'HOLD',
                       reservation_id = ?,
                       hold_expires_at = ?,
                       version = version + 1,
                       updated_at = ?
                 WHERE screening_id = ?
                   AND seat_id = ?
                   AND status = 'AVAILABLE'
                """)) {
      ps.setString(1, reservationId.value());
      ps.setLong(2, toMillis(expiresAt));
      ps.setLong(3, toMillis(now));
      ps.setString(4, screeningId.value());
      ps.setString(5, seat.value());
      return ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to hold seat: " + seat.value(), e);
    }
  }

  private void markSoldOne(
      ReservationId reservationId, SeatId seat, TicketId ticketId, Instant now) {
    try (PreparedStatement ps =
        connection()
            .prepareStatement(
                """
                UPDATE seat_states
                   SET status = 'SOLD',
                       ticket_id = ?,
                       hold_expires_at = NULL,
                       version = version + 1,
                       updated_at = ?
                 WHERE reservation_id = ?
                   AND seat_id = ?
                   AND status = 'HOLD'
                """)) {
      ps.setString(1, ticketId.value());
      ps.setLong(2, toMillis(now));
      ps.setString(3, reservationId.value());
      ps.setString(4, seat.value());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to mark sold: " + seat.value(), e);
    }
  }

  private static SeatState toSeatState(ResultSet rs) throws SQLException {
    return new SeatState(
        new ScreeningId(rs.getString("screening_id")),
        new SeatId(rs.getString("seat_id")),
        SeatStateStatus.valueOf(rs.getString("status")),
        nullableReservationId(rs),
        nullableInstant(rs, "hold_expires_at"),
        nullableTicketId(rs),
        rs.getInt("price"),
        rs.getLong("version"),
        toInstant(rs.getLong("updated_at")));
  }

  private Connection connection() {
    return uow.currentConnection();
  }

  private static ReservationId nullableReservationId(ResultSet rs) throws SQLException {
    String value = rs.getString("reservation_id");
    return value == null ? null : new ReservationId(value);
  }

  private static TicketId nullableTicketId(ResultSet rs) throws SQLException {
    String value = rs.getString("ticket_id");
    return value == null ? null : new TicketId(value);
  }

  private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : toInstant(value);
  }

  private static long toMillis(Instant instant) {
    return instant.toEpochMilli();
  }

  private static Instant toInstant(long millis) {
    return Instant.ofEpochMilli(millis);
  }
}
