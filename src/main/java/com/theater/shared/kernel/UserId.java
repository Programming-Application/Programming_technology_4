package com.theater.shared.kernel;

/** User aggregate identifier. cross-BC 参照あり (ordering/reservation/ticketing から user 起点で参照)。 */
public record UserId(String value) implements Identifier {

  public UserId {
    Identifier.requireNonBlank(value, "user id");
  }
}
