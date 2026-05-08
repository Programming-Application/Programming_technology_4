package com.theater.ordering.domain;

import com.theater.shared.kernel.Money;
import com.theater.shared.kernel.OrderId;
import com.theater.shared.kernel.ReservationId;
import com.theater.shared.kernel.ScreeningId;
import com.theater.shared.kernel.UserId;
import java.time.Instant;
import java.util.Objects;

/** Order aggregate. docs/data_model.md §4 の orders テーブルに対応する domain record。 */
public record Order(
    OrderId id,
    UserId userId,
    ScreeningId screeningId,
    ReservationId reservationId,
    Money totalAmount,
    PaymentStatus paymentStatus,
    OrderStatus orderStatus,
    Instant purchasedAt,
    Instant canceledAt,
    Instant createdAt,
    Instant updatedAt,
    long version) {

  public Order {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(screeningId, "screeningId");
    Objects.requireNonNull(reservationId, "reservationId");
    Objects.requireNonNull(totalAmount, "totalAmount");
    Objects.requireNonNull(paymentStatus, "paymentStatus");
    Objects.requireNonNull(orderStatus, "orderStatus");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (version < 0) {
      throw new IllegalArgumentException("version must not be negative");
    }
    validateStatus(paymentStatus, orderStatus, purchasedAt, canceledAt);
  }

  private static void validateStatus(
      PaymentStatus paymentStatus,
      OrderStatus orderStatus,
      Instant purchasedAt,
      Instant canceledAt) {
    if (orderStatus == OrderStatus.CREATED && purchasedAt != null) {
      throw new IllegalArgumentException("CREATED order must not have purchasedAt");
    }
    if (orderStatus == OrderStatus.CONFIRMED
        && (paymentStatus != PaymentStatus.PAID || purchasedAt == null)) {
      throw new IllegalArgumentException("CONFIRMED order requires PAID payment and purchasedAt");
    }
    if (orderStatus == OrderStatus.CANCELED && canceledAt == null) {
      throw new IllegalArgumentException("CANCELED order requires canceledAt");
    }
  }
}
