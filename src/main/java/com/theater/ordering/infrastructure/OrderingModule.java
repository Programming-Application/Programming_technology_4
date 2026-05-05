package com.theater.ordering.infrastructure;

import com.theater.shared.di.Container;
import com.theater.shared.di.Module;

/**
 * ordering BC の DI バインディング (skeleton)。OR-01 で Repository、OR-02 で MockPaymentGateway、 OR-04 で
 * CheckoutUseCase を bind。
 */
public final class OrderingModule implements Module {

  @Override
  public void bind(Container container) {
    // TODO(OR-01): OrderRepository / PaymentRepository / RefundRepository
    // TODO(OR-02): MockPaymentGateway を PaymentGateway として bind
    // TODO(OR-03): StartCheckoutUseCase
    // TODO(OR-04): CheckoutUseCase  ← 重量Tx
    // TODO(OR-05): CancelOrderUseCase
  }
}
