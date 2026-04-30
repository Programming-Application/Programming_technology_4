package com.theater.testkit;

import com.theater.shared.kernel.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * テスト用の固定時計。{@link #advance(Duration)} で時刻を進められる。
 *
 * <p>パターン: Strategy (Clock の実装差替)。
 */
public final class FixedClock implements Clock {

    private volatile Instant now;

    public FixedClock(Instant initial) {
        this.now = Objects.requireNonNull(initial, "initial");
    }

    public static FixedClock at(Instant t) {
        return new FixedClock(t);
    }

    @Override
    public Instant now() {
        return now;
    }

    public synchronized void advance(Duration d) {
        this.now = this.now.plus(Objects.requireNonNull(d));
    }

    public synchronized void set(Instant t) {
        this.now = Objects.requireNonNull(t);
    }
}
