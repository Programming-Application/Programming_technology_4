package com.theater.shared.error;

/** 決済失敗。Checkout Tx 全体を Rollback するために RuntimeException を継承。 */
public final class PaymentFailedException extends DomainException {

  private static final long serialVersionUID = 1L;

  public PaymentFailedException(String message) {
    super(message);
  }
}
