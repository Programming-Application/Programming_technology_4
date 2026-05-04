package com.theater.shared.kernel;

/**
 * Movie aggregate identifier.
 *
 * <p>Cross-BC 参照あり (catalog で master、reservation/ordering/ticketing でも参照されうる)。
 */
public record MovieId(String value) implements Identifier {

  public MovieId {
    Identifier.requireNonBlank(value, "movie id");
  }
}
