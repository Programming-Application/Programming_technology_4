-- =====================================================================
-- V030: ordering (orders / payments / refunds)
-- Owner: Person C
-- See docs/data_model.md §4.
-- =====================================================================

CREATE TABLE orders (
  order_id        TEXT PRIMARY KEY,
  user_id         TEXT NOT NULL,
  screening_id    TEXT NOT NULL,
  reservation_id  TEXT NOT NULL UNIQUE,          -- 1予約=1注文 (二重課金防止)
  total_amount    INTEGER NOT NULL CHECK (total_amount >= 0),
  payment_status  TEXT NOT NULL DEFAULT 'PENDING'
                  CHECK (payment_status IN ('PENDING','PAID','FAILED','REFUNDED')),
  order_status    TEXT NOT NULL DEFAULT 'CREATED'
                  CHECK (order_status IN ('CREATED','CONFIRMED','CANCELED')),
  purchased_at    INTEGER,
  canceled_at     INTEGER,
  created_at      INTEGER NOT NULL,
  updated_at      INTEGER NOT NULL,
  version         INTEGER NOT NULL DEFAULT 0,
  -- 整合: CONFIRMED は PAID のみ / CANCELED は canceledAt 必須
  CHECK (
       (order_status = 'CREATED'   AND purchased_at IS NULL)
    OR (order_status = 'CONFIRMED' AND payment_status = 'PAID' AND purchased_at IS NOT NULL)
    OR (order_status = 'CANCELED'  AND canceled_at IS NOT NULL)
  ),
  FOREIGN KEY (user_id)        REFERENCES users(user_id)               ON DELETE RESTRICT,
  FOREIGN KEY (screening_id)   REFERENCES screenings(screening_id)     ON DELETE RESTRICT,
  FOREIGN KEY (reservation_id) REFERENCES reservations(reservation_id) ON DELETE RESTRICT
);
CREATE INDEX idx_orders_user ON orders(user_id, created_at DESC);

CREATE TABLE payments (
  payment_id   TEXT PRIMARY KEY,
  order_id     TEXT NOT NULL UNIQUE,             -- 1注文=1決済 (二重課金最終防壁)
  amount       INTEGER NOT NULL CHECK (amount >= 0),
  status       TEXT NOT NULL CHECK (status IN ('PENDING','PAID','FAILED','REFUNDED')),
  processed_at INTEGER,
  created_at   INTEGER NOT NULL,
  updated_at   INTEGER NOT NULL,
  version      INTEGER NOT NULL DEFAULT 0,
  FOREIGN KEY (order_id) REFERENCES orders(order_id) ON DELETE RESTRICT
);

CREATE TABLE refunds (
  refund_id   TEXT PRIMARY KEY,
  order_id    TEXT NOT NULL UNIQUE,              -- 1注文=1返金 (二重返金防止)
  amount      INTEGER NOT NULL CHECK (amount >= 0),
  reason      TEXT NOT NULL,
  refunded_at INTEGER NOT NULL,
  FOREIGN KEY (order_id) REFERENCES orders(order_id) ON DELETE RESTRICT
);
