package com.theater.ordering.domain;

import com.theater.shared.kernel.OrderId;
import com.theater.shared.kernel.ReservationId;
import com.theater.shared.kernel.UserId;
import java.util.List;
import java.util.Optional;

/** Write-side repository for orders. */
public interface OrderRepository {

  Optional<Order> findById(OrderId id);

  Optional<Order> findByReservationId(ReservationId reservationId);

  List<Order> findByUser(UserId userId);

  void save(Order order);
}
