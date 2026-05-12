package com.theater.shared.session;

import com.theater.shared.kernel.ReservationId;
import com.theater.shared.kernel.ScreeningId;
import com.theater.shared.kernel.UserId;
import java.util.Objects;
import java.util.Optional;

/** UI flow state shared between JavaFX controllers. */
public final class CurrentSelection {

  private ScreeningId screeningId;
  private UserId userId;
  private ReservationId activeReservationId;

  public synchronized void selectScreening(ScreeningId selectedScreeningId, UserId selectedUserId) {
    screeningId = Objects.requireNonNull(selectedScreeningId, "selectedScreeningId");
    userId = Objects.requireNonNull(selectedUserId, "selectedUserId");
    activeReservationId = null;
  }

  public synchronized void setCurrentUser(UserId currentUserId) {
    userId = Objects.requireNonNull(currentUserId, "currentUserId");
  }

  public synchronized ScreeningId currentScreening() {
    return currentScreeningOptional()
        .orElseThrow(() -> new IllegalStateException("No screening has been selected"));
  }

  public synchronized Optional<ScreeningId> currentScreeningOptional() {
    return Optional.ofNullable(screeningId);
  }

  public synchronized UserId currentUser() {
    return currentUserOptional()
        .orElseThrow(() -> new IllegalStateException("No user is available in the session"));
  }

  public synchronized Optional<UserId> currentUserOptional() {
    return Optional.ofNullable(userId);
  }

  public synchronized void setActiveReservation(ReservationId reservationId) {
    activeReservationId = Objects.requireNonNull(reservationId, "reservationId");
  }

  public synchronized ReservationId activeReservation() {
    return activeReservationOptional()
        .orElseThrow(() -> new IllegalStateException("No active reservation is available"));
  }

  public synchronized Optional<ReservationId> activeReservationOptional() {
    return Optional.ofNullable(activeReservationId);
  }
}
