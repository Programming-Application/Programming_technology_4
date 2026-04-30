package com.theater.shared.kernel;

import com.theater.shared.error.MismatchedCurrencyException;
import java.util.Objects;

/**
 * 金額の値オブジェクト。{@code int} の生加算を禁じるための型。
 *
 * <p>不変条件:
 *
 * <ul>
 *   <li>非負 (負金額は表現不可)
 *   <li>同一通貨同士でしか算術を行えない
 *   <li>オーバーフローは {@link Math#addExact} 等で検出 → {@link ArithmeticException}
 * </ul>
 *
 * <p>パターン: Value Object (DDD)
 */
public record Money(long minorUnits, Currency currency) {

  public Money {
    if (minorUnits < 0) {
      throw new IllegalArgumentException("Money must be non-negative: " + minorUnits);
    }
    Objects.requireNonNull(currency, "currency");
  }

  public static Money jpy(long yen) {
    return new Money(yen, Currency.JPY);
  }

  public static Money zero(Currency currency) {
    return new Money(0L, currency);
  }

  public Money plus(Money other) {
    ensureSameCurrency(other);
    return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
  }

  public Money minus(Money other) {
    ensureSameCurrency(other);
    long result = Math.subtractExact(minorUnits, other.minorUnits);
    if (result < 0) {
      throw new IllegalStateException("Money cannot become negative: " + result);
    }
    return new Money(result, currency);
  }

  public Money times(int multiplier) {
    if (multiplier < 0) {
      throw new IllegalArgumentException("multiplier must be non-negative: " + multiplier);
    }
    return new Money(Math.multiplyExact(minorUnits, (long) multiplier), currency);
  }

  public boolean isZero() {
    return minorUnits == 0L;
  }

  private void ensureSameCurrency(Money other) {
    if (this.currency != other.currency) {
      throw new MismatchedCurrencyException(this.currency, other.currency);
    }
  }
}
