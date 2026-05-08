package com.theater.ordering.domain;

import com.theater.shared.kernel.OrderId;
import java.util.Optional;

/** Write-side repository for refunds. */
public interface RefundRepository {

  Optional<Refund> findByOrderId(OrderId orderId);

  void save(Refund refund);
}
