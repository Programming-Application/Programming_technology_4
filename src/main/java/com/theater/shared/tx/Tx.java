package com.theater.shared.tx;

/**
 * トランザクション伝播モード。Spring の {@code Propagation} のサブセット。
 *
 * <ul>
 *   <li>{@link #REQUIRED}: 既存 Tx があれば join、なければ新規。
 *   <li>{@link #REQUIRES_NEW}: 既存があっても必ず新規 Tx (現状未対応・将来用)。
 *   <li>{@link #READ_ONLY}: read-only モードで Tx 開始 (commit はする)。
 * </ul>
 */
public enum Tx {
  REQUIRED,
  REQUIRES_NEW,
  READ_ONLY
}
