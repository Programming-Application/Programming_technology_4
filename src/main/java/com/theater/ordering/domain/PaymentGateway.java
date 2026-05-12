package com.theater.ordering.domain;

import com.theater.shared.error.PaymentFailedException;
import com.theater.shared.kernel.Money;
import java.time.Instant;
import java.util.Objects;

/**
 * 決済ゲートウェイのポート。本プロジェクトは外部決済なしのため実装は {@code MockPaymentGateway} のみ。
 *
 * <p>パターン: Strategy (実装差し替えで Atomicity テストに使う)。
 */
public interface PaymentGateway {

  /**
   * 決済を実行する。
   *
   * @param amount 決済金額
   * @return 決済結果
   * @throws PaymentFailedException 決済失敗時 (Tx 全体を Rollback させる)
   */
  PaymentResult charge(Money amount);

  /** 決済結果。外部トランザクション ID と処理時刻を保持する。 */
  record PaymentResult(String externalTransactionId, Instant processedAt) {

    public PaymentResult {
      Objects.requireNonNull(externalTransactionId, "externalTransactionId");
      Objects.requireNonNull(processedAt, "processedAt");
      if (externalTransactionId.isBlank()) {
        throw new IllegalArgumentException("externalTransactionId must not be blank");
      }
    }
  }
}
