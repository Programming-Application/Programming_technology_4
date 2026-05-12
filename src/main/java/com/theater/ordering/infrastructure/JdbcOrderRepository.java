package com.theater.ordering.infrastructure;

import com.theater.ordering.domain.Order;
import com.theater.ordering.domain.OrderRepository;
import com.theater.ordering.domain.OrderStatus;
import com.theater.ordering.domain.PaymentStatus;
import com.theater.shared.error.OptimisticLockException;
import com.theater.shared.kernel.Currency;
import com.theater.shared.kernel.Money;
import com.theater.shared.kernel.OrderId;
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

/** JDBC implementation for orders. */
final class JdbcOrderRepository implements OrderRepository {

  private final UnitOfWork uow;

  JdbcOrderRepository(UnitOfWork uow) {
    this.uow = Objects.requireNonNull(uow, "uow");
  }

  @Override
  public Optional<Order> findById(OrderId id) {
    try (PreparedStatement ps =
        connection()
            .prepareStatement(
                """
                SELECT order_id, user_id, screening_id, reservation_id,
                       total_amount, payment_status, order_status,
                       purchased_at, canceled_at, created_at, updated_at, version
                  FROM orders
                 WHERE order_id = ?
                """)) {
      ps.setString(1, id.value());
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(toOrder(rs)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to find order: " + id.value(), e);
    }
  }

  @Override
  public Optional<Order> findByReservationId(ReservationId reservationId) {
    try (PreparedStatement ps =
        connection()
            .prepareStatement(
                """
                SELECT order_id, user_id, screening_id, reservation_id,
                       total_amount, payment_status, order_status,
                       purchased_at, canceled_at, created_at, updated_at, version
                  FROM orders
                 WHERE reservation_id = ?
                """)) {
      ps.setString(1, reservationId.value());
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(toOrder(rs)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw new IllegalStateException(
          "Failed to find order by reservation: " + reservationId.value(), e);
    }
  }

  @Override
  public List<Order> findByUser(UserId userId) {
    try (PreparedStatement ps =
        connection()
            .prepareStatement(
                """
                SELECT order_id, user_id, screening_id, reservation_id,
                       total_amount, payment_status, order_status,
                       purchased_at, canceled_at, created_at, updated_at, version
                  FROM orders
                 WHERE user_id = ?
                 ORDER BY created_at DESC
                """)) {
      ps.setString(1, userId.value());
      List<Order> orders = new ArrayList<>();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          orders.add(toOrder(rs));
        }
      }
      return orders;
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to find orders by user: " + userId.value(), e);
    }
  }

  @Override
  public void save(Order order) {
    Objects.requireNonNull(order, "order");
    if (findById(order.id()).isPresent()) {
      update(order);
    } else {
      insert(order);
    }
  }

  private void insert(Order order) {
    try (PreparedStatement ps =
        connection()
            .prepareStatement(
                """
                INSERT INTO orders(
                  order_id, user_id, screening_id, reservation_id,
                  total_amount, payment_status, order_status,
                  purchased_at, canceled_at, created_at, updated_at, version)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
      ps.setString(1, order.id().value());
      ps.setString(2, order.userId().value());
      ps.setString(3, order.screeningId().value());
      ps.setString(4, order.reservationId().value());
      ps.setLong(5, order.totalAmount().minorUnits());
      ps.setString(6, order.paymentStatus().name());
      ps.setString(7, order.orderStatus().name());
      bindNullableInstant(ps, 8, order.purchasedAt());
      bindNullableInstant(ps, 9, order.canceledAt());
      ps.setLong(10, order.createdAt().toEpochMilli());
      ps.setLong(11, order.updatedAt().toEpochMilli());
      ps.setLong(12, order.version());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to insert order: " + order.id().value(), e);
    }
  }

  private void update(Order order) {
    long previousVersion = order.version() - 1;
    try (PreparedStatement ps =
        connection()
            .prepareStatement(
                """
                UPDATE orders
                   SET user_id = ?,
                       screening_id = ?,
                       reservation_id = ?,
                       total_amount = ?,
                       payment_status = ?,
                       order_status = ?,
                       purchased_at = ?,
                       canceled_at = ?,
                       updated_at = ?,
                       version = version + 1
                 WHERE order_id = ?
                   AND version = ?
                """)) {
      ps.setString(1, order.userId().value());
      ps.setString(2, order.screeningId().value());
      ps.setString(3, order.reservationId().value());
      ps.setLong(4, order.totalAmount().minorUnits());
      ps.setString(5, order.paymentStatus().name());
      ps.setString(6, order.orderStatus().name());
      bindNullableInstant(ps, 7, order.purchasedAt());
      bindNullableInstant(ps, 8, order.canceledAt());
      ps.setLong(9, order.updatedAt().toEpochMilli());
      ps.setString(10, order.id().value());
      ps.setLong(11, previousVersion);
      int updated = ps.executeUpdate();
      if (updated != 1) {
        throw new OptimisticLockException("Order", order.id().value());
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to update order: " + order.id().value(), e);
    }
  }

  private static Order toOrder(ResultSet rs) throws SQLException {
    return new Order(
        new OrderId(rs.getString("order_id")),
        new UserId(rs.getString("user_id")),
        new ScreeningId(rs.getString("screening_id")),
        new ReservationId(rs.getString("reservation_id")),
        new Money(rs.getLong("total_amount"), Currency.JPY),
        PaymentStatus.valueOf(rs.getString("payment_status")),
        OrderStatus.valueOf(rs.getString("order_status")),
        nullableInstant(rs, "purchased_at"),
        nullableInstant(rs, "canceled_at"),
        Instant.ofEpochMilli(rs.getLong("created_at")),
        Instant.ofEpochMilli(rs.getLong("updated_at")),
        rs.getLong("version"));
  }

  private Connection connection() {
    return uow.currentConnection();
  }

  private static void bindNullableInstant(PreparedStatement ps, int index, Instant instant)
      throws SQLException {
    if (instant == null) {
      ps.setObject(index, null);
    } else {
      ps.setLong(index, instant.toEpochMilli());
    }
  }

  private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : Instant.ofEpochMilli(value);
  }
}
