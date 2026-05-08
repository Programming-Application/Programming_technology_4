package com.theater.ordering.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.shared.kernel.Money;
import com.theater.shared.kernel.OrderId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PaymentTest {

  private static final Instant NOW = Instant.parse("2026-05-04T00:00:00Z");

  @Test
  void pending_payment_constructs_without_processed_at() {
    var payment = pending();
    assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
  }

  @Test
  void paid_payment_requires_processed_at() {
    assertThatThrownBy(
            () ->
                new Payment(
                    new PaymentId("p-1"),
                    new OrderId("o-1"),
                    Money.jpy(1500),
                    PaymentStatus.PAID,
                    null,
                    NOW,
                    NOW,
                    0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Payment pending() {
    return new Payment(
        new PaymentId("p-1"),
        new OrderId("o-1"),
        Money.jpy(1500),
        PaymentStatus.PENDING,
        null,
        NOW,
        NOW,
        0);
  }
}
