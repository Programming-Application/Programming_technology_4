-- =====================================================================
-- V010: catalog (movies / screens / seats / screenings)
-- Owner: Person B
-- See docs/data_model.md §2.
-- =====================================================================

CREATE TABLE movies (
  movie_id          TEXT PRIMARY KEY,
  title             TEXT NOT NULL,
  description       TEXT NOT NULL DEFAULT '',
  duration_minutes  INTEGER NOT NULL CHECK (duration_minutes > 0),
  is_published      INTEGER NOT NULL DEFAULT 0 CHECK (is_published IN (0,1)),
  created_at        INTEGER NOT NULL,
  updated_at        INTEGER NOT NULL,
  version           INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE screens (
  screen_id    TEXT PRIMARY KEY,
  name         TEXT NOT NULL,
  total_seats  INTEGER NOT NULL CHECK (total_seats > 0),
  created_at   INTEGER NOT NULL,
  updated_at   INTEGER NOT NULL
);

CREATE TABLE seats (
  screen_id  TEXT NOT NULL,
  seat_id    TEXT NOT NULL,
  row        TEXT NOT NULL,
  number     INTEGER NOT NULL CHECK (number > 0),
  seat_type  TEXT NOT NULL CHECK (seat_type IN ('NORMAL','PREMIUM','WHEELCHAIR')),
  is_active  INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0,1)),
  PRIMARY KEY (screen_id, seat_id),
  UNIQUE (screen_id, row, number),
  FOREIGN KEY (screen_id) REFERENCES screens(screen_id) ON DELETE RESTRICT
);

CREATE TABLE screenings (
  screening_id          TEXT PRIMARY KEY,
  movie_id              TEXT NOT NULL,
  screen_id             TEXT NOT NULL,
  start_time            INTEGER NOT NULL,
  end_time              INTEGER NOT NULL,
  sales_start_at        INTEGER NOT NULL,
  sales_end_at          INTEGER NOT NULL,
  status                TEXT NOT NULL DEFAULT 'SCHEDULED'
                        CHECK (status IN ('SCHEDULED','OPEN','CLOSED','CANCELED')),
  is_private            INTEGER NOT NULL DEFAULT 0 CHECK (is_private IN (0,1)),
  available_seat_count  INTEGER NOT NULL DEFAULT 0,
  reserved_seat_count   INTEGER NOT NULL DEFAULT 0,
  sold_seat_count       INTEGER NOT NULL DEFAULT 0,
  last_updated          INTEGER NOT NULL,
  created_at            INTEGER NOT NULL,
  updated_at            INTEGER NOT NULL,
  version               INTEGER NOT NULL DEFAULT 0,
  CHECK (start_time < end_time),
  CHECK (sales_start_at <= sales_end_at),
  CHECK (sales_end_at <= start_time),
  CHECK (available_seat_count >= 0),
  CHECK (reserved_seat_count >= 0),
  CHECK (sold_seat_count >= 0),
  FOREIGN KEY (movie_id) REFERENCES movies(movie_id) ON DELETE RESTRICT,
  FOREIGN KEY (screen_id) REFERENCES screens(screen_id) ON DELETE RESTRICT
);

CREATE INDEX idx_screenings_start ON screenings(start_time);
CREATE INDEX idx_screenings_movie ON screenings(movie_id, start_time);
CREATE INDEX idx_screenings_status ON screenings(status, start_time);
