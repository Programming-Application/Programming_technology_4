package com.theater.ordering.domain;

import com.theater.shared.kernel.Identifier;

/** Payment identifier. ordering BC 内部 ID のため shared/kernel には置かない。 */
public record PaymentId(String value) implements Identifier {

  public PaymentId {
    Identifier.requireNonBlank(value, "payment id");
  }
}
