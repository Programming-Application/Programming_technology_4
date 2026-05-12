package com.theater.reservation.domain;

import com.theater.shared.kernel.ReservationId;
import com.theater.shared.kernel.ScreeningId;
import com.theater.shared.kernel.UserId;
import java.time.Instant;
import java.util.Objects;

/** Reservation aggregate. docs/data_model.md §3 の reservations テーブルに対応する domain record。 */
public record Reservation(
    ReservationId id,
    UserId userId,
    ScreeningId screeningId,
    ReservationStatus status,
    Instant expiresAt,
    Instant createdAt,
    Instant updatedAt,
    long version) {

  public Reservation {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(screeningId, "screeningId");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (version < 0) {
      throw new IllegalArgumentException("version must not be negative");
    }
    if (status == ReservationStatus.HOLD && expiresAt == null) {
      throw new IllegalArgumentException("HOLD status requires expiresAt");
    }
  }

  public Reservation toCanceled(Instant now) {
    Objects.requireNonNull(now, "now");
    return new Reservation(
        id, userId, screeningId, ReservationStatus.CANCELED, null, createdAt, now, version + 1);
  }

  public Reservation toExpired(Instant now) {
    Objects.requireNonNull(now, "now");
    return new Reservation(
        id, userId, screeningId, ReservationStatus.EXPIRED, null, createdAt, now, version + 1);
  }

  public Reservation toConfirmed(Instant now) {
    Objects.requireNonNull(now, "now");
    return new Reservation(
        id, userId, screeningId, ReservationStatus.CONFIRMED, null, createdAt, now, version + 1);
  }
}
