package com.theater.reservation.domain;

import com.theater.shared.kernel.ReservationId;
import com.theater.shared.kernel.UserId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Write-side repository for reservations. */
public interface ReservationRepository {

  Optional<Reservation> findById(ReservationId id);

  List<Reservation> findActiveByUser(UserId userId);

  List<ReservationId> findExpiring(Instant now, int limit);

  void save(Reservation reservation);
}
