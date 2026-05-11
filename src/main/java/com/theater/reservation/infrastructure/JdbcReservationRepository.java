package com.theater.reservation.infrastructure;

import com.theater.reservation.domain.Reservation;
import com.theater.reservation.domain.ReservationRepository;
import com.theater.reservation.domain.ReservationStatus;
import com.theater.shared.error.OptimisticLockException;
import com.theater.shared.kernel.ReservationId;
import com.theater.shared.kernel.ScreeningId;
import com.theater.shared.kernel.UserId;
import com.theater.shared.tx.UnitOfWork;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** JDBC implementation for reservations. */
final class JdbcReservationRepository implements ReservationRepository {

  private final UnitOfWork uow;

  JdbcReservationRepository(UnitOfWork uow) {
    this.uow = Objects.requireNonNull(uow, "uow");
  }

  @Override
  public Optional<Reservation> findById(ReservationId id) {
    try (PreparedStatement ps =
        connection()
            .prepareStatement(
                """
                SELECT reservation_id, user_id, screening_id, status, expires_at,
                       created_at, updated_at, version
                  FROM reservations
                 WHERE reservation_id = ?
                """)) {
      ps.setString(1, id.value());
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(toReservation(rs)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to find reservation: " + id.value(), e);
    }
  }

  @Override
  public List<Reservation> findActiveByUser(UserId userId) {
    try (PreparedStatement ps =
        connection()
            .prepareStatement(
                """
                SELECT reservation_id, user_id, screening_id, status, expires_at,
                       created_at, updated_at, version
                  FROM reservations
                 WHERE user_id = ?
                   AND status = 'HOLD'
                 ORDER BY created_at
                """)) {
      ps.setString(1, userId.value());
      List<Reservation> reservations = new ArrayList<>();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          reservations.add(toReservation(rs));
        }
      }
      return reservations;
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to find active reservations", e);
    }
  }

  @Override
  public void save(Reservation reservation) {
    Objects.requireNonNull(reservation, "reservation");
    if (findById(reservation.id()).isPresent()) {
      update(reservation);
    } else {
      insert(reservation);
    }
  }

  private void insert(Reservation reservation) {
    try (PreparedStatement ps =
        connection()
            .prepareStatement(
                """
                INSERT INTO reservations(
                  reservation_id, user_id, screening_id, status, expires_at,
                  created_at, updated_at, version)
                VALUES (?,?,?,?,?,?,?,?)
                """)) {
      bindReservation(ps, reservation);
      ps.setLong(8, reservation.version());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(
          "Failed to insert reservation: " + reservation.id().value(), e);
    }
  }

  private void update(Reservation reservation) {
    long previousVersion = reservation.version() - 1;
    try (PreparedStatement ps =
        connection()
            .prepareStatement(
                """
                UPDATE reservations
                   SET user_id = ?,
                       screening_id = ?,
                       status = ?,
                       expires_at = ?,
                       created_at = ?,
                       updated_at = ?,
                       version = version + 1
                 WHERE reservation_id = ?
                   AND version = ?
                """)) {
      ps.setString(1, reservation.userId().value());
      ps.setString(2, reservation.screeningId().value());
      ps.setString(3, reservation.status().name());
      bindNullableInstant(ps, 4, reservation.expiresAt());
      ps.setLong(5, toMillis(reservation.createdAt()));
      ps.setLong(6, toMillis(reservation.updatedAt()));
      ps.setString(7, reservation.id().value());
      ps.setLong(8, previousVersion);
      int updated = ps.executeUpdate();
      if (updated != 1) {
        throw new OptimisticLockException("Reservation", reservation.id().value());
      }
    } catch (SQLException e) {
      throw new IllegalStateException(
          "Failed to update reservation: " + reservation.id().value(), e);
    }
  }

  private static Reservation toReservation(ResultSet rs) throws SQLException {
    return new Reservation(
        new ReservationId(rs.getString("reservation_id")),
        new UserId(rs.getString("user_id")),
        new ScreeningId(rs.getString("screening_id")),
        ReservationStatus.valueOf(rs.getString("status")),
        nullableInstant(rs, "expires_at"),
        toInstant(rs.getLong("created_at")),
        toInstant(rs.getLong("updated_at")),
        rs.getLong("version"));
  }

  private static void bindReservation(PreparedStatement ps, Reservation reservation)
      throws SQLException {
    ps.setString(1, reservation.id().value());
    ps.setString(2, reservation.userId().value());
    ps.setString(3, reservation.screeningId().value());
    ps.setString(4, reservation.status().name());
    bindNullableInstant(ps, 5, reservation.expiresAt());
    ps.setLong(6, toMillis(reservation.createdAt()));
    ps.setLong(7, toMillis(reservation.updatedAt()));
  }

  private Connection connection() {
    return uow.currentConnection();
  }

  private static void bindNullableInstant(PreparedStatement ps, int index, Instant instant)
      throws SQLException {
    if (instant == null) {
      ps.setObject(index, null);
    } else {
      ps.setLong(index, toMillis(instant));
    }
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
