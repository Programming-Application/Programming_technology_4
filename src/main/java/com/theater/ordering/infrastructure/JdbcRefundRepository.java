package com.theater.ordering.infrastructure;

import com.theater.ordering.domain.Refund;
import com.theater.ordering.domain.RefundRepository;
import com.theater.shared.kernel.Currency;
import com.theater.shared.kernel.Money;
import com.theater.shared.kernel.OrderId;
import com.theater.shared.tx.UnitOfWork;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** JDBC implementation for refunds. */
final class JdbcRefundRepository implements RefundRepository {

  private final UnitOfWork uow;

  JdbcRefundRepository(UnitOfWork uow) {
    this.uow = Objects.requireNonNull(uow, "uow");
  }

  @Override
  public Optional<Refund> findByOrderId(OrderId orderId) {
    try (PreparedStatement ps =
        connection()
            .prepareStatement(
                """
                SELECT refund_id, order_id, amount, reason, refunded_at
                  FROM refunds
                 WHERE order_id = ?
                """)) {
      ps.setString(1, orderId.value());
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(toRefund(rs)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to find refund by order: " + orderId.value(), e);
    }
  }

  @Override
  public void save(Refund refund) {
    Objects.requireNonNull(refund, "refund");
    try (PreparedStatement ps =
        connection()
            .prepareStatement(
                """
                INSERT INTO refunds(refund_id, order_id, amount, reason, refunded_at)
                VALUES (?,?,?,?,?)
                """)) {
      ps.setString(1, refund.refundId());
      ps.setString(2, refund.orderId().value());
      ps.setLong(3, refund.amount().minorUnits());
      ps.setString(4, refund.reason());
      ps.setLong(5, refund.refundedAt().toEpochMilli());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to save refund: " + refund.refundId(), e);
    }
  }

  private static Refund toRefund(ResultSet rs) throws SQLException {
    return new Refund(
        rs.getString("refund_id"),
        new OrderId(rs.getString("order_id")),
        new Money(rs.getLong("amount"), Currency.JPY),
        rs.getString("reason"),
        Instant.ofEpochMilli(rs.getLong("refunded_at")));
  }

  private Connection connection() {
    return uow.currentConnection();
  }
}
