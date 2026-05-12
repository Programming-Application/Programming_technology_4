package com.theater.ordering.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.ordering.domain.PaymentGateway;
import com.theater.shared.error.PaymentFailedException;
import com.theater.shared.kernel.Currency;
import com.theater.shared.kernel.Money;
import com.theater.testkit.FixedClock;
import com.theater.testkit.IncrementingIdGenerator;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MockPaymentGatewayTest {

  static final Instant NOW = Instant.parse("2026-05-12T00:00:00Z");
  static final Money AMOUNT = new Money(3000L, Currency.JPY);

  private MockPaymentGateway gateway;

  @BeforeEach
  void setup() {
    gateway = new MockPaymentGateway(FixedClock.at(NOW), new IncrementingIdGenerator("txn-"));
  }

  @Nested
  class AlwaysSucceed {

    @Test
    void default_setting_succeeds_and_returns_result() {
      PaymentGateway.PaymentResult result = gateway.charge(AMOUNT);

      assertThat(result.externalTransactionId()).isEqualTo("txn-1");
      assertThat(result.processedAt()).isEqualTo(NOW);
    }

    @Test
    void explicit_always_succeed_resets_state_and_succeeds() {
      gateway.alwaysFail();
      gateway.alwaysSucceed();

      PaymentGateway.PaymentResult result = gateway.charge(AMOUNT);

      assertThat(result.externalTransactionId()).isNotBlank();
    }

    @Test
    void call_count_increments_on_each_charge() {
      gateway.charge(AMOUNT);
      gateway.charge(AMOUNT);

      assertThat(gateway.callCount()).isEqualTo(2);
    }

    @Test
    void always_succeed_resets_call_count() {
      gateway.charge(AMOUNT);
      gateway.alwaysSucceed();

      assertThat(gateway.callCount()).isZero();
    }
  }

  @Nested
  class AlwaysFail {

    @Test
    void throws_payment_failed_exception() {
      gateway.alwaysFail();

      assertThatThrownBy(() -> gateway.charge(AMOUNT))
          .isInstanceOf(PaymentFailedException.class)
          .hasMessageContaining("always fails");
    }

    @Test
    void increments_call_count_before_throwing() {
      gateway.alwaysFail();

      assertThatThrownBy(() -> gateway.charge(AMOUNT)).isInstanceOf(PaymentFailedException.class);
      assertThat(gateway.callCount()).isEqualTo(1);
    }
  }

  @Nested
  class FailNthCall {

    @Test
    void first_call_succeeds_second_fails_third_succeeds() {
      gateway.failNthCall(2);

      PaymentGateway.PaymentResult first = gateway.charge(AMOUNT);
      assertThat(first).isNotNull();

      assertThatThrownBy(() -> gateway.charge(AMOUNT))
          .isInstanceOf(PaymentFailedException.class)
          .hasMessageContaining("call 2");

      PaymentGateway.PaymentResult third = gateway.charge(AMOUNT);
      assertThat(third).isNotNull();
    }

    @Test
    void fail_on_first_call() {
      gateway.failNthCall(1);

      assertThatThrownBy(() -> gateway.charge(AMOUNT)).isInstanceOf(PaymentFailedException.class);
    }

    @Test
    void fail_nth_call_resets_call_count() {
      gateway.charge(AMOUNT);
      gateway.failNthCall(1);

      assertThat(gateway.callCount()).isZero();
    }

    @Test
    void invalid_n_throws() {
      assertThatThrownBy(() -> gateway.failNthCall(0)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  class DelayMs {

    @Test
    void zero_delay_succeeds_immediately() {
      gateway.delayMs(0);

      PaymentGateway.PaymentResult result = gateway.charge(AMOUNT);
      assertThat(result).isNotNull();
    }

    @Test
    void negative_delay_throws() {
      assertThatThrownBy(() -> gateway.delayMs(-1)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  class NullGuard {

    @Test
    void null_amount_throws() {
      assertThatThrownBy(() -> gateway.charge(null)).isInstanceOf(NullPointerException.class);
    }
  }
}
