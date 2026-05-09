package com.theater.reservation.domain;

import com.theater.shared.kernel.ScreeningId;
import java.time.Instant;

/** Reservation BC から screenings の座席集計値を更新するための小さなポート。 */
public interface ScreeningCounterRepository {

  void adjust(
      ScreeningId screeningId,
      int availableDelta,
      int reservedDelta,
      int soldDelta,
      Instant now);
}
