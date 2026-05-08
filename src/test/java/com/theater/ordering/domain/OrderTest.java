package com.theater.ordering.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.shared.kernel.Money;
import com.theater.shared.kernel.OrderId;
import com.theater.shared.kernel.ReservationId;
import com.theater.shared.kernel.ScreeningId;
import com.theater.shared.kernel.UserId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OrderTest {

  private static final Instant NOW = Instant.parse("2026-05-04T00:00:00Z");

  @Test
  void created_order_constructs_without_purchased_at() {
    var order = created();
    assertThat(order.orderStatus()).isEqualTo(OrderStatus.CREATED);
  }

  @Test
  void confirmed_order_requires_paid_payment_and_purchased_at() {
    assertThatThrownBy(
            () ->
                new Order(
                    new OrderId("o-1"),
                    new UserId("u-1"),
                    new ScreeningId("s-1"),
                    new ReservationId("r-1"),
                    Money.jpy(1500),
                    PaymentStatus.PENDING,
                    OrderStatus.CONFIRMED,
                    null,
                    null,
                    NOW,
                    NOW,
                    0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void canceled_order_requires_canceled_at() {
    assertThatThrownBy(
            () ->
                new Order(
                    new OrderId("o-1"),
                    new UserId("u-1"),
                    new ScreeningId("s-1"),
                    new ReservationId("r-1"),
                    Money.jpy(1500),
                    PaymentStatus.PAID,
                    OrderStatus.CANCELED,
                    NOW,
                    null,
                    NOW,
                    NOW,
                    0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Order created() {
    return new Order(
        new OrderId("o-1"),
        new UserId("u-1"),
        new ScreeningId("s-1"),
        new ReservationId("r-1"),
        Money.jpy(1500),
        PaymentStatus.PENDING,
        OrderStatus.CREATED,
        null,
        null,
        NOW,
        NOW,
        0);
  }
}
