-- =====================================================================
-- V001: shared kernel + identity
-- Owner: Person A
-- See docs/data_model.md §1 for the spec.
-- =====================================================================

-- ---- users ----
CREATE TABLE users (
  user_id        TEXT    PRIMARY KEY,
  email          TEXT    NOT NULL UNIQUE,
  name           TEXT    NOT NULL,
  password_hash  TEXT    NOT NULL,
  role           TEXT    NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'ADMIN')),
  created_at     INTEGER NOT NULL,
  updated_at     INTEGER NOT NULL,
  version        INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_users_role ON users(role);

-- ---- domain events outbox ----
-- Outbox パターン: Tx 内で書いた行を別ワーカが配信する。
-- published_at IS NULL の行が未配信。
CREATE TABLE domain_events_outbox (
  event_id        TEXT    PRIMARY KEY,
  aggregate_type  TEXT    NOT NULL,
  aggregate_id    TEXT    NOT NULL,
  event_type      TEXT    NOT NULL,
  payload_json    TEXT    NOT NULL,
  occurred_at     INTEGER NOT NULL,
  published_at    INTEGER
);

-- 未配信を効率良く拾うパーシャル INDEX (SQLite 3.8+ 対応)
CREATE INDEX idx_outbox_unpublished
  ON domain_events_outbox(occurred_at)
  WHERE published_at IS NULL;
