package com.theater.ordering.infrastructure;

import com.theater.ordering.domain.OrderRepository;
import com.theater.ordering.domain.PaymentRepository;
import com.theater.ordering.domain.RefundRepository;
import com.theater.shared.di.Container;
import com.theater.shared.di.Module;
import com.theater.shared.tx.UnitOfWork;

/** ordering BC の DI バインディング。 */
public final class OrderingModule implements Module {

  @Override
  public void bind(Container container) {
    container.registerSingleton(
        OrderRepository.class, c -> new JdbcOrderRepository(c.resolve(UnitOfWork.class)));
    container.registerSingleton(
        PaymentRepository.class, c -> new JdbcPaymentRepository(c.resolve(UnitOfWork.class)));
    container.registerSingleton(
        RefundRepository.class, c -> new JdbcRefundRepository(c.resolve(UnitOfWork.class)));
    // TODO(OR-02): MockPaymentGateway を PaymentGateway として bind
    // TODO(OR-03): StartCheckoutUseCase
    // TODO(OR-04): CheckoutUseCase ← 重量Tx
    // TODO(OR-05): CancelOrderUseCase
  }
}
