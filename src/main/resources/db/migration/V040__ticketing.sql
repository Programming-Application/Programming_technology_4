-- =====================================================================
-- V040: ticketing (tickets)
-- Owner: Person A
-- See docs/data_model.md §5.
-- =====================================================================

CREATE TABLE tickets (
  ticket_id     TEXT    PRIMARY KEY,
  order_id      TEXT    NOT NULL,
  screening_id  TEXT    NOT NULL,
  movie_id      TEXT    NOT NULL,
  screen_id     TEXT    NOT NULL,
  seat_id       TEXT    NOT NULL,
  user_id       TEXT    NOT NULL,
  price         INTEGER NOT NULL CHECK (price >= 0),
  status        TEXT    NOT NULL DEFAULT 'ACTIVE'
                CHECK (status IN ('ACTIVE', 'USED', 'CANCELED', 'REFUNDED')),
  purchased_at  INTEGER NOT NULL,
  used_at       INTEGER,
  canceled_at   INTEGER,
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL,
  version       INTEGER NOT NULL DEFAULT 0,

  -- 状態と関連列の整合
  CHECK (
       (status = 'ACTIVE'   AND used_at IS NULL     AND canceled_at IS NULL)
    OR (status = 'USED'     AND used_at IS NOT NULL AND canceled_at IS NULL)
    OR (status = 'CANCELED' AND canceled_at IS NOT NULL)
    OR (status = 'REFUNDED' AND canceled_at IS NOT NULL)
  ),

  -- order_id は OR-01 で orders テーブルが作られた後に V050 で FK 追加予定。
  -- 本マイグレーション時点では参照先テーブルが存在しないため、FK 制約はここでは付けない。
  -- TODO(OR-01): V050 で `FOREIGN KEY (order_id) REFERENCES orders(order_id) ON DELETE RESTRICT` を追加

  FOREIGN KEY (screening_id) REFERENCES screenings(screening_id) ON DELETE RESTRICT,
  FOREIGN KEY (movie_id)     REFERENCES movies(movie_id)         ON DELETE RESTRICT,
  FOREIGN KEY (screen_id, seat_id) REFERENCES seats(screen_id, seat_id) ON DELETE RESTRICT,
  FOREIGN KEY (user_id)      REFERENCES users(user_id)           ON DELETE RESTRICT
);

-- ★ ダブルブッキング最終防壁 (partial UNIQUE INDEX)
-- 同一 screening の同一 seat に複数の ACTIVE ticket が物理的に存在不可。
-- 一度 USED / CANCELED / REFUNDED になれば再発券可能 (上映会キャンセル → 払戻 → 別客に再発券のようなフロー想定)。
CREATE UNIQUE INDEX uq_tickets_active_seat
  ON tickets(screening_id, seat_id) WHERE status = 'ACTIVE';

CREATE INDEX idx_tickets_user  ON tickets(user_id, status);
CREATE INDEX idx_tickets_order ON tickets(order_id);
