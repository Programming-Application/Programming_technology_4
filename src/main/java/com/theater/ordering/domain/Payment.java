package com.theater.ordering.domain;

import com.theater.shared.kernel.Money;
import com.theater.shared.kernel.OrderId;
import java.time.Instant;
import java.util.Objects;

/** Payment record for a single order. */
public record Payment(
    PaymentId id,
    OrderId orderId,
    Money amount,
    PaymentStatus status,
    Instant processedAt,
    Instant createdAt,
    Instant updatedAt,
    long version) {

  public Payment {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(orderId, "orderId");
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (version < 0) {
      throw new IllegalArgumentException("version must not be negative");
    }
    if ((status == PaymentStatus.PAID || status == PaymentStatus.FAILED) && processedAt == null) {
      throw new IllegalArgumentException("finished payment requires processedAt");
    }
  }
}
