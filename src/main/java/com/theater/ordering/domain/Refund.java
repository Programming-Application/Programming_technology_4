package com.theater.ordering.domain;

import com.theater.shared.kernel.Identifier;
import com.theater.shared.kernel.Money;
import com.theater.shared.kernel.OrderId;
import java.time.Instant;
import java.util.Objects;

/** Refund record for a single order. */
public record Refund(
    String refundId, OrderId orderId, Money amount, String reason, Instant refundedAt) {

  public Refund {
    Identifier.requireNonBlank(refundId, "refund id");
    Objects.requireNonNull(orderId, "orderId");
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(refundedAt, "refundedAt");
    if (reason.isBlank()) {
      throw new IllegalArgumentException("reason must not be blank");
    }
  }
}
