package com.theater.shared.kernel;

/**
 * Order aggregate identifier.
 *
 * <p>本来は PLAT-04 で B が ordering 側 interface とセットで導入する予定だったが、ticketing の {@code Ticket}
 * エンティティが本型を参照するため PLAT-03 で先行導入。PLAT-04 ではこの ID を再定義しない。
 */
public record OrderId(String value) implements Identifier {

  public OrderId {
    Identifier.requireNonBlank(value, "order id");
  }
}
