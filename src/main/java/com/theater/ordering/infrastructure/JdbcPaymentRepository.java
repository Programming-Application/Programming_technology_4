package com.theater.ordering.infrastructure;

import com.theater.ordering.domain.Payment;
import com.theater.ordering.domain.PaymentId;
import com.theater.ordering.domain.PaymentRepository;
import com.theater.ordering.domain.PaymentStatus;
import com.theater.shared.error.OptimisticLockException;
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

/** JDBC implementation for payments. */
final class JdbcPaymentRepository implements PaymentRepository {

  private final UnitOfWork uow;

  JdbcPaymentRepository(UnitOfWork uow) {
    this.uow = Objects.requireNonNull(uow, "uow");
  }

  @Override
  public Optional<Payment> findByOrderId(OrderId orderId) {
    try (PreparedStatement ps =
        connection()
            .prepareStatement(
                """
                SELECT payment_id, order_id, amount, status,
                       processed_at, created_at, updated_at, version
                  FROM payments
                 WHERE order_id = ?
                """)) {
      ps.setString(1, orderId.value());
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(toPayment(rs)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to find payment by order: " + orderId.value(), e);
    }
  }

  @Override
  public void save(Payment payment) {
    Objects.requireNonNull(payment, "payment");
    if (existsById(payment.id())) {
      update(payment);
    } else {
      insert(payment);
    }
  }

  private boolean existsById(PaymentId id) {
    try (PreparedStatement ps =
        connection().prepareStatement("SELECT 1 FROM payments WHERE payment_id = ?")) {
      ps.setString(1, id.value());
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to check payment existence: " + id.value(), e);
    }
  }

  private void insert(Payment payment) {
    try (PreparedStatement ps =
        connection()
            .prepareStatement(
                """
                INSERT INTO payments(
                  payment_id, order_id, amount, status,
                  processed_at, created_at, updated_at, version)
                VALUES (?,?,?,?,?,?,?,?)
                """)) {
      ps.setString(1, payment.id().value());
      ps.setString(2, payment.orderId().value());
      ps.setLong(3, payment.amount().minorUnits());
      ps.setString(4, payment.status().name());
      bindNullableInstant(ps, 5, payment.processedAt());
      ps.setLong(6, payment.createdAt().toEpochMilli());
      ps.setLong(7, payment.updatedAt().toEpochMilli());
      ps.setLong(8, payment.version());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to insert payment: " + payment.id().value(), e);
    }
  }

  private void update(Payment payment) {
    long previousVersion = payment.version() - 1;
    try (PreparedStatement ps =
        connection()
            .prepareStatement(
                """
                UPDATE payments
                   SET amount = ?,
                       status = ?,
                       processed_at = ?,
                       updated_at = ?,
                       version = version + 1
                 WHERE payment_id = ?
                   AND version = ?
                """)) {
      ps.setLong(1, payment.amount().minorUnits());
      ps.setString(2, payment.status().name());
      bindNullableInstant(ps, 3, payment.processedAt());
      ps.setLong(4, payment.updatedAt().toEpochMilli());
      ps.setString(5, payment.id().value());
      ps.setLong(6, previousVersion);
      int updated = ps.executeUpdate();
      if (updated != 1) {
        throw new OptimisticLockException("Payment", payment.id().value());
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to update payment: " + payment.id().value(), e);
    }
  }

  private static Payment toPayment(ResultSet rs) throws SQLException {
    return new Payment(
        new PaymentId(rs.getString("payment_id")),
        new OrderId(rs.getString("order_id")),
        new Money(rs.getLong("amount"), Currency.JPY),
        PaymentStatus.valueOf(rs.getString("status")),
        nullableInstant(rs, "processed_at"),
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
