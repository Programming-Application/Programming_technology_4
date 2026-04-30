package com.theater.shared.error;

import com.theater.shared.kernel.Currency;

/** 異なる通貨同士での金額演算を試みたとき。 */
public final class MismatchedCurrencyException extends DomainException {

  private static final long serialVersionUID = 1L;

  public MismatchedCurrencyException(Currency lhs, Currency rhs) {
    super("Mismatched currency: " + lhs + " vs " + rhs);
  }
}
