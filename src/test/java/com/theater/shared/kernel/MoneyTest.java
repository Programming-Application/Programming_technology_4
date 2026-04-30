package com.theater.shared.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.shared.error.MismatchedCurrencyException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Nested
    class Construction {

        @Test
        void rejects_negative_amount() {
            assertThatThrownBy(() -> new Money(-1, Currency.JPY))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void zero_is_allowed() {
            assertThat(Money.zero(Currency.JPY).isZero()).isTrue();
        }

        @Test
        void factory_jpy_uses_yen_as_minor_unit() {
            assertThat(Money.jpy(1500))
                    .extracting(Money::minorUnits, Money::currency)
                    .containsExactly(1500L, Currency.JPY);
        }
    }

    @Nested
    class Arithmetic {

        @Test
        void plus_adds_same_currency() {
            Money sum = Money.jpy(1500).plus(Money.jpy(2500));
            assertThat(sum).isEqualTo(Money.jpy(4000));
        }

        @Test
        void minus_throws_when_result_would_be_negative() {
            assertThatThrownBy(() -> Money.jpy(100).minus(Money.jpy(200)))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void times_multiplies_minor_units() {
            assertThat(Money.jpy(500).times(3)).isEqualTo(Money.jpy(1500));
        }

        @Test
        void times_rejects_negative_multiplier() {
            assertThatThrownBy(() -> Money.jpy(100).times(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class CurrencyEnforcement {

        @Test
        void same_currency_arithmetic_passes() {
            // 本案件は JPY のみ。複数通貨が増えたら cross-currency テストを追加。
            assertThat(Money.jpy(1).plus(Money.jpy(2)).currency()).isEqualTo(Currency.JPY);
        }

        @Test
        void mismatched_currency_exception_message_carries_currency() {
            MismatchedCurrencyException ex =
                    new MismatchedCurrencyException(Currency.JPY, Currency.JPY);
            assertThat(ex).hasMessageContaining("JPY");
        }
    }
}
