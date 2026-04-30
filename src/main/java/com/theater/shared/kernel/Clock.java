package com.theater.shared.kernel;

import java.time.Instant;

/**
 * 現在時刻の抽象。テスト時は固定可能 ({@code testkit.FixedClock})。
 *
 * <p>パターン: Strategy。
 */
@FunctionalInterface
public interface Clock {

  /** 実時間で動作する標準実装。 */
  Clock SYSTEM = Instant::now;

  Instant now();
}
