package com.theater.reservation.domain;

import com.theater.shared.kernel.ReservationId;
import com.theater.shared.kernel.ScreeningId;
import com.theater.shared.kernel.SeatId;
import com.theater.shared.kernel.TicketId;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Write-side repository for screening seat states. */
public interface SeatStateRepository {

  List<SeatState> findByScreening(ScreeningId screeningId);

  /** RV-02 HoldSeats 用。影響行数が要求座席数と一致しなければ呼び元が衝突として扱う。 */
  int tryHold(
      ScreeningId screeningId,
      List<SeatId> seats,
      ReservationId reservationId,
      Instant expiresAt,
      Instant now);

  int releaseByReservation(ReservationId reservationId, Instant now);

  /** OR-05 CancelOrder 用。SOLD 座席を AVAILABLE に戻す。返却値は解放した座席数。 */
  int releaseSoldByReservation(ReservationId reservationId, Instant now);

  void markSold(ReservationId reservationId, Map<SeatId, TicketId> seatToTicket, Instant now);

  void markExpired(List<ReservationId> reservationIds, Instant now);
}
