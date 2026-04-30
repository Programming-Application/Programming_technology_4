package com.theater.shared.tx;

import java.sql.Connection;
import java.util.function.Supplier;

/**
 * ユースケース1本のトランザクション境界を表す抽象。
 *
 * <p>使い方は {@link TransactionalUseCase} を継承するのが標準。直接呼ぶ場合:
 *
 * <pre>{@code
 * Result r = uow.execute(Tx.REQUIRED, () -> {
 *     repo.save(...);
 *     return computeSomething();
 * });
 * }</pre>
 */
public interface UnitOfWork {

  /**
   * Tx 内で {@code work} を実行する。例外が伝播したら全 Rollback、正常終了なら Commit。
   *
   * @param mode 伝播モード
   * @param work 業務処理
   * @return {@code work} の戻り値
   */
  <R> R execute(Tx mode, Supplier<R> work);

  /**
   * 戻り値を返さない write Tx の糖衣構文。
   *
   * <p>{@code uow.execute(Tx.REQUIRED, () -> { foo(); return null; })} のような <em>return null;</em>
   * を毎回書かなくて済む。
   */
  default void executeVoid(Tx mode, Runnable work) {
    execute(
        mode,
        () -> {
          work.run();
          return null;
        });
  }

  /** 実行中の Tx に紐づく {@link Connection}。Tx 外で呼ぶと {@link IllegalStateException}。 */
  Connection currentConnection();
}
