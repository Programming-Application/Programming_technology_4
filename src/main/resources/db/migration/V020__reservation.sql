-- =====================================================================
-- V020: reservation (reservations / seat_states)
-- Owner: Person C
-- See docs/data_model.md §3.
-- =====================================================================

CREATE TABLE reservations (
  reservation_id  TEXT PRIMARY KEY,
  user_id         TEXT NOT NULL,
  screening_id    TEXT NOT NULL,
  status          TEXT NOT NULL CHECK (status IN ('HOLD','CONFIRMED','CANCELED','EXPIRED')),
  expires_at      INTEGER,
  created_at      INTEGER NOT NULL,
  updated_at      INTEGER NOT NULL,
  version         INTEGER NOT NULL DEFAULT 0,
  CHECK ((status = 'HOLD' AND expires_at IS NOT NULL)
      OR (status <> 'HOLD' AND expires_at IS NULL)),
  FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE RESTRICT,
  FOREIGN KEY (screening_id) REFERENCES screenings(screening_id) ON DELETE RESTRICT
);

CREATE INDEX idx_reservations_user_active
  ON reservations(user_id, status, created_at);
CREATE INDEX idx_reservations_screening
  ON reservations(screening_id);

CREATE TABLE seat_states (
  screening_id      TEXT NOT NULL,
  seat_id           TEXT NOT NULL,
  status            TEXT NOT NULL CHECK (status IN ('AVAILABLE','HOLD','SOLD','BLOCKED')),
  reservation_id    TEXT,
  hold_expires_at   INTEGER,
  ticket_id         TEXT,
  price             INTEGER NOT NULL CHECK (price >= 0),
  version           INTEGER NOT NULL DEFAULT 0,
  updated_at        INTEGER NOT NULL,
  PRIMARY KEY (screening_id, seat_id),
  CHECK (
    (status = 'AVAILABLE'
      AND reservation_id IS NULL
      AND hold_expires_at IS NULL
      AND ticket_id IS NULL)
    OR (status = 'HOLD'
      AND reservation_id IS NOT NULL
      AND hold_expires_at IS NOT NULL
      AND ticket_id IS NULL)
    OR (status = 'SOLD'
      AND reservation_id IS NOT NULL
      AND hold_expires_at IS NULL
      AND ticket_id IS NOT NULL)
    OR (status = 'BLOCKED'
      AND hold_expires_at IS NULL
      AND ticket_id IS NULL)
  ),
  FOREIGN KEY (screening_id) REFERENCES screenings(screening_id) ON DELETE RESTRICT,
  FOREIGN KEY (screening_id, seat_id) REFERENCES seats(screen_id, seat_id) ON DELETE RESTRICT,
  FOREIGN KEY (reservation_id) REFERENCES reservations(reservation_id) ON DELETE SET NULL
);

CREATE INDEX idx_seat_states_reservation
  ON seat_states(reservation_id, status);
CREATE INDEX idx_seat_states_screening_status
  ON seat_states(screening_id, status);
