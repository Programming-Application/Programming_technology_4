package com.theater.shared.kernel;

/**
 * Screen (theater room) identifier.
 *
 * <p>Cross-BC 参照あり (catalog で master、ticketing 等でも参照される)。
 */
public record ScreenId(String value) implements Identifier {

  public ScreenId {
    Identifier.requireNonBlank(value, "screen id");
  }
}
