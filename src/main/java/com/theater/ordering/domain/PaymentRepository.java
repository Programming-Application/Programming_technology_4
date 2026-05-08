package com.theater.ordering.domain;

import com.theater.shared.kernel.OrderId;
import java.util.Optional;

/** Write-side repository for payments. */
public interface PaymentRepository {

  Optional<Payment> findByOrderId(OrderId orderId);

  void save(Payment payment);
}
