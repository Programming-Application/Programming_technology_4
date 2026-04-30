# データモデル (SQLite スキーマ)

> `data_structure.md` (Firestore版) を SQLite (3.45+) 向けに正規化した**正本**。
> マイグレーションは `src/main/resources/db/migration/V###__*.sql` (Flyway) として配置する。

---

## 0. 共通方針

- **型**: SQLite の動的型を踏まえつつ、`TEXT` (UUID/列挙) / `INTEGER` (epoch millis / 数値 / boolean) に統一。
- **時刻**: epoch millis (`INTEGER`)。アプリ側は `Instant`。`Clock` strategy でテスト時固定。
- **boolean**: `INTEGER CHECK (col IN (0,1))`。
- **金額**: `INTEGER` (最小通貨単位 = 円)。アプリ側は `record Money(long minorUnits, Currency currency)`。
- **ID**: UUID v7 を `TEXT` で。`shared.kernel.IdGenerator` で生成。
- **接続**: 起動時必ず `PRAGMA foreign_keys = ON`、`PRAGMA journal_mode = WAL`、`PRAGMA busy_timeout = 5000`、`PRAGMA synchronous = NORMAL`。
- **楽観ロック**: `version INTEGER NOT NULL DEFAULT 0` を主要集約テーブルに付与し、`UPDATE ... WHERE version = ? SET version = version + 1` で衝突検出。
- **悲観ロック**: 高頻度衝突パス (`HoldSeats`) は `BEGIN IMMEDIATE` で書込ロックを早期取得 + `UPDATE WHERE status='AVAILABLE'` の影響行数で in-flight 衝突を検出。
- **DELETE禁止**: マスタ系は論理削除 (`is_published`, `status='CANCELED'` 等)。FKは原則 `ON DELETE RESTRICT`。

---

## 1. V001__shared_identity.sql (🅰)

```sql
-- メタテーブル (Outbox は domain events、必要時 V001で先に作る)
CREATE TABLE users (
  user_id        TEXT PRIMARY KEY,
  email          TEXT NOT NULL UNIQUE,
  name           TEXT NOT NULL,
  password_hash  TEXT NOT NULL,
  role           TEXT NOT NULL DEFAULT 'USER' CHECK (role IN ('USER','ADMIN')),
  created_at     INTEGER NOT NULL,
  updated_at     INTEGER NOT NULL,
  version        INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE domain_events_outbox (
  event_id        TEXT PRIMARY KEY,
  aggregate_type  TEXT NOT NULL,
  aggregate_id    TEXT NOT NULL,
  event_type      TEXT NOT NULL,
  payload_json    TEXT NOT NULL,
  occurred_at     INTEGER NOT NULL,
  published_at    INTEGER
);
CREATE INDEX idx_outbox_unpublished ON domain_events_outbox(occurred_at) WHERE published_at IS NULL;
```

---

## 2. V010__catalog.sql (🅱)

```sql
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
  seat_id    TEXT NOT NULL,                 -- 例: "A-10"
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
  CHECK (reserved_seat_count  >= 0),
  CHECK (sold_seat_count      >= 0),
  FOREIGN KEY (movie_id)  REFERENCES movies(movie_id)   ON DELETE RESTRICT,
  FOREIGN KEY (screen_id) REFERENCES screens(screen_id) ON DELETE RESTRICT
);
CREATE INDEX idx_screenings_start  ON screenings(start_time);
CREATE INDEX idx_screenings_movie  ON screenings(movie_id, start_time);
CREATE INDEX idx_screenings_status ON screenings(status, start_time);
```

> **時間重複チェック** (同一 `screen_id` の `[start_time, end_time)` が重複しない) は SQLite の EXCLUDE 制約が無いためアプリ側で検出 + テストで保証。

---

## 3. V020__reservation.sql (🅲)

```sql
CREATE TABLE reservations (
  reservation_id  TEXT PRIMARY KEY,
  user_id         TEXT NOT NULL,
  screening_id    TEXT NOT NULL,
  status          TEXT NOT NULL DEFAULT 'HOLD'
                  CHECK (status IN ('HOLD','CONFIRMED','EXPIRED','CANCELED')),
  expires_at      INTEGER,
  created_at      INTEGER NOT NULL,
  updated_at      INTEGER NOT NULL,
  version         INTEGER NOT NULL DEFAULT 0,
  CHECK (
    (status = 'HOLD' AND expires_at IS NOT NULL)
    OR status <> 'HOLD'
  ),
  FOREIGN KEY (user_id)      REFERENCES users(user_id)            ON DELETE RESTRICT,
  FOREIGN KEY (screening_id) REFERENCES screenings(screening_id)  ON DELETE RESTRICT
);
CREATE INDEX idx_reservations_user        ON reservations(user_id, status);
CREATE INDEX idx_reservations_expiring    ON reservations(status, expires_at) WHERE status='HOLD';

-- 上映会×座席のステート (ダブルブッキング防止の中核)
CREATE TABLE seat_states (
  screening_id     TEXT NOT NULL,
  seat_id          TEXT NOT NULL,
  status           TEXT NOT NULL DEFAULT 'AVAILABLE'
                   CHECK (status IN ('AVAILABLE','HOLD','SOLD','BLOCKED')),
  reservation_id   TEXT,
  hold_expires_at  INTEGER,
  ticket_id        TEXT,
  price            INTEGER NOT NULL DEFAULT 0 CHECK (price >= 0),
  version          INTEGER NOT NULL DEFAULT 0,
  updated_at       INTEGER NOT NULL,
  PRIMARY KEY (screening_id, seat_id),
  -- 状態と関連列の整合 (★重要不変条件)
  CHECK (
       (status = 'AVAILABLE' AND reservation_id IS NULL  AND hold_expires_at IS NULL  AND ticket_id IS NULL)
    OR (status = 'HOLD'      AND reservation_id IS NOT NULL AND hold_expires_at IS NOT NULL AND ticket_id IS NULL)
    OR (status = 'SOLD'      AND reservation_id IS NOT NULL AND ticket_id IS NOT NULL)
    OR (status = 'BLOCKED'   AND ticket_id IS NULL)
  ),
  FOREIGN KEY (screening_id)   REFERENCES screenings(screening_id) ON DELETE CASCADE,
  FOREIGN KEY (reservation_id) REFERENCES reservations(reservation_id) ON DELETE SET NULL
);
CREATE INDEX idx_seat_states_reservation ON seat_states(reservation_id);
CREATE INDEX idx_seat_states_status      ON seat_states(screening_id, status);
```

**HoldSeats の SQL パターン (ダブルブッキング防止)**:
```sql
BEGIN IMMEDIATE;
-- 全座席が AVAILABLE であることを要求 (where 条件で衝突検出)
UPDATE seat_states
   SET status='HOLD',
       reservation_id=:rid,
       hold_expires_at=:exp,
       version=version+1,
       updated_at=:now
 WHERE screening_id=:sid
   AND seat_id IN (?, ?, ?)
   AND status='AVAILABLE';
-- ↑ rowsAffected != 要求座席数なら衝突あり → ROLLBACK
INSERT INTO reservations(...) VALUES (...);
COMMIT;
```

---

## 4. V030__ordering.sql (🅲)

```sql
CREATE TABLE orders (
  order_id        TEXT PRIMARY KEY,
  user_id         TEXT NOT NULL,
  screening_id    TEXT NOT NULL,
  reservation_id  TEXT NOT NULL UNIQUE,        -- 1予約=1注文
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
  -- 整合: CONFIRMED は PAID のみ / CANCELED は purchased_at は持ちうる
  CHECK (
       (order_status = 'CREATED'   AND purchased_at IS NULL)
    OR (order_status = 'CONFIRMED' AND payment_status = 'PAID' AND purchased_at IS NOT NULL)
    OR (order_status = 'CANCELED'  AND canceled_at IS NOT NULL)
  ),
  FOREIGN KEY (user_id)        REFERENCES users(user_id)             ON DELETE RESTRICT,
  FOREIGN KEY (screening_id)   REFERENCES screenings(screening_id)   ON DELETE RESTRICT,
  FOREIGN KEY (reservation_id) REFERENCES reservations(reservation_id) ON DELETE RESTRICT
);
CREATE INDEX idx_orders_user ON orders(user_id, created_at DESC);

CREATE TABLE payments (
  payment_id    TEXT PRIMARY KEY,
  order_id      TEXT NOT NULL UNIQUE,
  amount        INTEGER NOT NULL CHECK (amount >= 0),
  status        TEXT NOT NULL CHECK (status IN ('PENDING','SUCCESS','FAILED','REFUNDED')),
  processed_at  INTEGER,
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL,
  version       INTEGER NOT NULL DEFAULT 0,
  FOREIGN KEY (order_id) REFERENCES orders(order_id) ON DELETE RESTRICT
);

-- 返金は別行で記録 (二重Refund禁止のため UNIQUE で守る)
CREATE TABLE refunds (
  refund_id     TEXT PRIMARY KEY,
  order_id      TEXT NOT NULL UNIQUE,
  amount        INTEGER NOT NULL CHECK (amount >= 0),
  reason        TEXT NOT NULL,
  refunded_at   INTEGER NOT NULL,
  FOREIGN KEY (order_id) REFERENCES orders(order_id) ON DELETE RESTRICT
);
```

**Checkout の Tx 内処理順序 (1つのBEGIN…COMMIT)**:
1. `SELECT … FROM reservations WHERE reservation_id=? AND status='HOLD' AND expires_at>:now` で行確保 + 期限再確認
2. `SELECT seat_id, price FROM seat_states WHERE reservation_id=?` で価格再計算
3. アプリで `total = SUM(price)` を再計算し、Command渡しの想定金額と一致を assert
4. `INSERT INTO orders(...)`
5. `MockPaymentGateway.charge(total)` 呼び出し ← 失敗例外なら Tx 全体 Rollback
6. `INSERT INTO payments(...)` PAID
7. `UPDATE reservations SET status='CONFIRMED' WHERE reservation_id=? AND status='HOLD' AND version=?` (楽観)
8. `UPDATE seat_states SET status='SOLD', ticket_id=:tid, hold_expires_at=NULL WHERE reservation_id=?`
9. `INSERT INTO tickets(...)` × N (`UNIQUE (screening_id, seat_id)` で最終防壁)
10. `INSERT INTO domain_events_outbox(...)` (`OrderConfirmed`, `TicketsIssued`)
11. `UPDATE screenings SET reserved_seat_count -= n, sold_seat_count += n, last_updated=:now WHERE screening_id=? AND version=?`
12. COMMIT

> 5番目で外部呼び出し (Mock) が来るが、Tx は単一プロセス内SQLite上の話なので**外部呼び出しを Tx 内に閉じてOK**。実運用なら 2-phase 化するが本案件では不要。

---

## 5. V040__ticketing.sql (🅰)

```sql
CREATE TABLE tickets (
  ticket_id      TEXT PRIMARY KEY,
  order_id       TEXT NOT NULL,
  screening_id   TEXT NOT NULL,
  movie_id       TEXT NOT NULL,
  screen_id      TEXT NOT NULL,
  seat_id        TEXT NOT NULL,
  user_id        TEXT NOT NULL,
  price          INTEGER NOT NULL CHECK (price >= 0),
  status         TEXT NOT NULL DEFAULT 'ACTIVE'
                 CHECK (status IN ('ACTIVE','USED','CANCELED','REFUNDED')),
  purchased_at   INTEGER NOT NULL,
  used_at        INTEGER,
  canceled_at    INTEGER,
  created_at     INTEGER NOT NULL,
  updated_at     INTEGER NOT NULL,
  version        INTEGER NOT NULL DEFAULT 0,
  -- ★同一上映の同一座席に複数 ACTIVE 不可 (ダブルブッキング最終防壁)
  -- SQLite はパーシャルUNIQUE可
  FOREIGN KEY (order_id)      REFERENCES orders(order_id)         ON DELETE RESTRICT,
  FOREIGN KEY (screening_id)  REFERENCES screenings(screening_id) ON DELETE RESTRICT,
  FOREIGN KEY (movie_id)      REFERENCES movies(movie_id)         ON DELETE RESTRICT,
  FOREIGN KEY (screen_id, seat_id) REFERENCES seats(screen_id, seat_id) ON DELETE RESTRICT,
  FOREIGN KEY (user_id)       REFERENCES users(user_id)           ON DELETE RESTRICT
);
CREATE UNIQUE INDEX uq_tickets_active_seat
  ON tickets(screening_id, seat_id) WHERE status='ACTIVE';
CREATE INDEX idx_tickets_user  ON tickets(user_id, status);
CREATE INDEX idx_tickets_order ON tickets(order_id);
```

---

## 6. ダブルブッキング防止: 多層防御サマリ

| 層 | メカニズム | 検証先 |
|---|---|---|
| L1 アプリ (Domain) | `Reservation` 集約の不変条件 (HOLD中 seat 集合は disjoint) | UnitTest |
| L2 アプリ (Tx 境界) | `BEGIN IMMEDIATE` + `UPDATE seat_states WHERE status='AVAILABLE'` の rowsAffected 検査 | Concurrency Test |
| L3 DB (CHECK) | `seat_states.CHECK` の状態×関連列の組合せ制約 | Repository Test |
| L4 DB (UNIQUE) | `tickets uq_tickets_active_seat (screening_id, seat_id) WHERE status='ACTIVE'` | Repository Test |

> L1〜L3 が破れても L4 で **必ず** 落ちる。これを `testing.md` の「最終防壁テスト」で保証する。

---

## 7. 集計値 (denormalized) の整合方針

`screenings.available_seat_count / reserved_seat_count / sold_seat_count` は **同一Tx内で `seat_states` の変更と一緒に更新** する。整合性は `testing.md` の Consistency テストで:
- COMMIT後に `SELECT COUNT(*) GROUP BY status FROM seat_states WHERE screening_id=?` を計算し、`screenings` の集計値と一致する
ことを invariants として全Tx後にassert する。

---

## 8. シードデータ

`src/test/resources/db/seed/*.sql` に以下を用意:
- `seed_users.sql` (USER×3, ADMIN×1)
- `seed_movies.sql` (Movie×2, screen×1, seats×30)
- `seed_screenings.sql` (上映会×2, seat_states を AVAILABLE で全件)

`testFixtures` 側に `Seeds.java` を置き、各テストクラスで `@BeforeEach` から呼び出せるようにする。

---

## 9. ER 概略

```
users 1───* reservations *───1 screenings *───1 movies
                  │                │
                  │                ├───1 screens *───* seats
                  │                │
                  │                └───* seat_states *───1 reservations (nullable)
                  │
                  └───1 orders 1───1 payments
                       │     ╲
                       │      ╲───1 refunds (nullable)
                       └───* tickets
```
