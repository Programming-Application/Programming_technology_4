package com.theater.ticketing.infrastructure;

import com.theater.shared.kernel.Currency;
import com.theater.shared.kernel.Money;
import com.theater.shared.kernel.MovieId;
import com.theater.shared.kernel.OrderId;
import com.theater.shared.kernel.ScreenId;
import com.theater.shared.kernel.ScreeningId;
import com.theater.shared.kernel.SeatId;
import com.theater.shared.kernel.TicketId;
import com.theater.shared.kernel.UserId;
import com.theater.shared.tx.UnitOfWork;
import com.theater.ticketing.domain.Ticket;
import com.theater.ticketing.domain.TicketRepository;
import com.theater.ticketing.domain.TicketStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link TicketRepository} の JDBC 実装。
 *
 * <p>{@link UnitOfWork#currentConnection()} 経由で現 Tx の Connection を取得する設計 (他 Repository と同じ
 * 流儀)。OR-04 Checkout 内で複数枚を同 Tx 内 INSERT する用途を主とする。
 *
 * <p>{@link #insert} は {@code uq_tickets_active_seat} (同一 screening の同一 seat に複数 ACTIVE 不可) 違反時に
 * SQLException → IllegalStateException で伝搬。呼出側 (OR-04) はこれを業務例外に翻訳する想定。
 */
final class JdbcTicketRepository implements TicketRepository {

  private final UnitOfWork uow;

  JdbcTicketRepository(UnitOfWork uow) {
    this.uow = Objects.requireNonNull(uow, "uow");
  }

  @Override
  public Optional<Ticket> findById(TicketId id) {
    try (PreparedStatement ps =
        connection()
            .prepareStatement(
                """
                SELECT ticket_id, order_id, screening_id, movie_id, screen_id, seat_id,
                       user_id, price, status, purchased_at, used_at, canceled_at,
                       created_at, updated_at, version
                  FROM tickets
                 WHERE ticket_id = ?
                """)) {
      ps.setString(1, id.value());
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(toTicket(rs)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to find ticket: " + id.value(), e);
    }
  }

  @Override
  public List<Ticket> findByUser(UserId userId) {
    try (PreparedStatement ps =
        connection()
            .prepareStatement(
                """
                SELECT ticket_id, order_id, screening_id, movie_id, screen_id, seat_id,
                       user_id, price, status, purchased_at, used_at, canceled_at,
                       created_at, updated_at, version
                  FROM tickets
                 WHERE user_id = ?
                 ORDER BY purchased_at DESC
                """)) {
      ps.setString(1, userId.value());
      List<Ticket> tickets = new ArrayList<>();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          tickets.add(toTicket(rs));
        }
      }
      return tickets;
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to find tickets by user: " + userId.value(), e);
    }
  }

  @Override
  public void insert(Ticket ticket) {
    Objects.requireNonNull(ticket, "ticket");
    try (PreparedStatement ps =
        connection()
            .prepareStatement(
                """
                INSERT INTO tickets(
                  ticket_id, order_id, screening_id, movie_id, screen_id, seat_id,
                  user_id, price, status, purchased_at, used_at, canceled_at,
                  created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
      ps.setString(1, ticket.id().value());
      ps.setString(2, ticket.orderId().value());
      ps.setString(3, ticket.screeningId().value());
      ps.setString(4, ticket.movieId().value());
      ps.setString(5, ticket.screenId().value());
      ps.setString(6, ticket.seatId().value());
      ps.setString(7, ticket.userId().value());
      ps.setLong(8, ticket.price().minorUnits());
      ps.setString(9, ticket.status().name());
      ps.setLong(10, ticket.purchasedAt().toEpochMilli());
      setNullableInstant(ps, 11, ticket.usedAt());
      setNullableInstant(ps, 12, ticket.canceledAt());
      ps.setLong(13, ticket.createdAt().toEpochMilli());
      ps.setLong(14, ticket.updatedAt().toEpochMilli());
      ps.setLong(15, ticket.version());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to insert ticket: " + ticket.id().value(), e);
    }
  }

  @Override
  public int cancelByOrderId(OrderId orderId, Instant canceledAt) {
    Objects.requireNonNull(orderId, "orderId");
    Objects.requireNonNull(canceledAt, "canceledAt");
    try (PreparedStatement ps =
        connection()
            .prepareStatement(
                """
                UPDATE tickets
                   SET status = 'CANCELED',
                       canceled_at = ?,
                       updated_at = ?,
                       version = version + 1
                 WHERE order_id = ?
                   AND status = 'ACTIVE'
                """)) {
      ps.setLong(1, canceledAt.toEpochMilli());
      ps.setLong(2, canceledAt.toEpochMilli());
      ps.setString(3, orderId.value());
      return ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to cancel tickets by order: " + orderId.value(), e);
    }
  }

  @Override
  public void markUsed(TicketId id, Instant usedAt) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(usedAt, "usedAt");
    try (PreparedStatement ps =
        connection()
            .prepareStatement(
                """
                UPDATE tickets
                   SET status = 'USED',
                       used_at = ?,
                       updated_at = ?,
                       version = version + 1
                 WHERE ticket_id = ?
                   AND status = 'ACTIVE'
                """)) {
      ps.setLong(1, usedAt.toEpochMilli());
      ps.setLong(2, usedAt.toEpochMilli());
      ps.setString(3, id.value());
      // 影響行数 0 の場合: 既に USED 等で更新不要 (冪等)。例外にはしない。
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to mark ticket used: " + id.value(), e);
    }
  }

  private Connection connection() {
    return uow.currentConnection();
  }

  private static void setNullableInstant(PreparedStatement ps, int idx, Instant value)
      throws SQLException {
    if (value == null) {
      ps.setNull(idx, Types.INTEGER);
    } else {
      ps.setLong(idx, value.toEpochMilli());
    }
  }

  private static Ticket toTicket(ResultSet rs) throws SQLException {
    return new Ticket(
        new TicketId(rs.getString("ticket_id")),
        new OrderId(rs.getString("order_id")),
        new ScreeningId(rs.getString("screening_id")),
        new MovieId(rs.getString("movie_id")),
        new ScreenId(rs.getString("screen_id")),
        new SeatId(rs.getString("seat_id")),
        new UserId(rs.getString("user_id")),
        // 本案件は JPY 単一通貨。Money(minor, JPY) で復元。
        new Money(rs.getLong("price"), Currency.JPY),
        TicketStatus.valueOf(rs.getString("status")),
        Instant.ofEpochMilli(rs.getLong("purchased_at")),
        nullableInstant(rs, "used_at"),
        nullableInstant(rs, "canceled_at"),
        Instant.ofEpochMilli(rs.getLong("created_at")),
        Instant.ofEpochMilli(rs.getLong("updated_at")),
        rs.getLong("version"));
  }

  private static Instant nullableInstant(ResultSet rs, String col) throws SQLException {
    long v = rs.getLong(col);
    return rs.wasNull() ? null : Instant.ofEpochMilli(v);
  }
}
