package com.theater.reservation.infrastructure;

import com.theater.reservation.domain.ScreeningCounterRepository;
import com.theater.shared.kernel.ScreeningId;
import com.theater.shared.tx.UnitOfWork;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;

/** JDBC implementation for updating screening seat counters. */
final class JdbcScreeningCounterRepository implements ScreeningCounterRepository {

  private final UnitOfWork uow;

  JdbcScreeningCounterRepository(UnitOfWork uow) {
    this.uow = Objects.requireNonNull(uow, "uow");
  }

  @Override
  public void adjust(
      ScreeningId screeningId,
      int availableDelta,
      int reservedDelta,
      int soldDelta,
      Instant now) {
    try (PreparedStatement ps =
        connection()
            .prepareStatement(
                """
                UPDATE screenings
                   SET available_seat_count = available_seat_count + ?,
                       reserved_seat_count = reserved_seat_count + ?,
                       sold_seat_count = sold_seat_count + ?,
                       last_updated = ?,
                       updated_at = ?,
                       version = version + 1
                 WHERE screening_id = ?
                """)) {
      ps.setInt(1, availableDelta);
      ps.setInt(2, reservedDelta);
      ps.setInt(3, soldDelta);
      ps.setLong(4, toMillis(now));
      ps.setLong(5, toMillis(now));
      ps.setString(6, screeningId.value());
      int updated = ps.executeUpdate();
      if (updated != 1) {
        throw new IllegalStateException("Screening counter not found: " + screeningId.value());
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to adjust screening counters", e);
    }
  }

  private Connection connection() {
    return uow.currentConnection();
  }

  private static long toMillis(Instant instant) {
    return instant.toEpochMilli();
  }
}
