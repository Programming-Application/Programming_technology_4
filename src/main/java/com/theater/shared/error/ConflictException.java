package com.theater.shared.error;

/** 在庫やリソースの衝突 (例: HOLD したい座席が既に HOLD/SOLD)。 */
public final class ConflictException extends DomainException {

  private static final long serialVersionUID = 1L;

  public ConflictException(String message) {
    super(message);
  }
}
