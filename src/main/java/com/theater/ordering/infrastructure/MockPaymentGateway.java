package com.theater.ordering.infrastructure;

import com.theater.ordering.domain.PaymentGateway;
import com.theater.shared.error.PaymentFailedException;
import com.theater.shared.kernel.Clock;
import com.theater.shared.kernel.IdGenerator;
import com.theater.shared.kernel.Money;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * テスト用の決済ゲートウェイ実装。外部決済サービスを使わず確定的に成功・失敗を制御できる。
 *
 * <p>パターン: Strategy ({@link PaymentGateway} 実装)。
 *
 * <p>スレッドセーフ: {@code volatile} フィールドで設定を保持。{@code callCount} は {@link AtomicInteger}。
 * 設定変更はテスト開始前に行い、並行呼び出し中には変更しない前提。
 */
public final class MockPaymentGateway implements PaymentGateway {

  private final Clock clock;
  private final IdGenerator idGen;

  private volatile double successRate = 1.0;
  private volatile long delayMillis = 0L;
  private volatile int failOnNthCall = -1;

  private final AtomicInteger callCount = new AtomicInteger(0);

  public MockPaymentGateway(Clock clock, IdGenerator idGen) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.idGen = Objects.requireNonNull(idGen, "idGen");
  }

  /** 設定をリセットして常に成功させる。 */
  public void alwaysSucceed() {
    successRate = 1.0;
    failOnNthCall = -1;
    delayMillis = 0L;
    callCount.set(0);
  }

  /** 常に {@link PaymentFailedException} を投げる。 */
  public void alwaysFail() {
    successRate = 0.0;
    failOnNthCall = -1;
    callCount.set(0);
  }

  /**
   * N 回目の呼び出しで必ず失敗させる。Atomicity テスト用。
   *
   * @param n 1始まりの呼び出し番号
   */
  public void failNthCall(int n) {
    if (n < 1) {
      throw new IllegalArgumentException("n must be >= 1");
    }
    successRate = 1.0;
    failOnNthCall = n;
    callCount.set(0);
  }

  /**
   * 各呼び出しに人工遅延を加える。並行実行テストで race condition を誘発させるために使う。
   *
   * @param ms 遅延ミリ秒
   */
  public void delayMs(long ms) {
    if (ms < 0) {
      throw new IllegalArgumentException("delay must be non-negative");
    }
    this.delayMillis = ms;
  }

  /** 現在までの呼び出し回数を返す。 */
  public int callCount() {
    return callCount.get();
  }

  @Override
  public PaymentResult charge(Money amount) {
    Objects.requireNonNull(amount, "amount");
    int call = callCount.incrementAndGet();

    applyDelay();

    if (failOnNthCall > 0 && call == failOnNthCall) {
      throw new PaymentFailedException(
          "Mock: forced failure on call " + call + " (failNthCall=" + failOnNthCall + ")");
    }
    if (successRate <= 0.0) {
      throw new PaymentFailedException("Mock: payment always fails");
    }

    return new PaymentResult(idGen.newId(), clock.now());
  }

  private void applyDelay() {
    long ms = delayMillis;
    if (ms <= 0L) {
      return;
    }
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
