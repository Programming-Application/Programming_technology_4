-- =====================================================================
-- V040: ticketing (tickets)
-- Owner: Person A
-- See docs/data_model.md §5.
-- =====================================================================

-- TODO(A): tickets テーブル + UNIQUE INDEX uq_tickets_active_seat (screening_id, seat_id) WHERE status='ACTIVE'
-- ↑ ダブルブッキング最終防壁。これは絶対消さないこと。
SELECT 1;
