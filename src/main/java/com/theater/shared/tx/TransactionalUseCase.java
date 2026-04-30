package com.theater.shared.tx;

import java.util.Objects;

/**
 * 書込ユースケースの基底クラス。
 *
 * <p>パターン:
 *
 * <ul>
 *   <li>Template Method: {@link #execute(Object)} の枠組み (validate → tx → handle) を固定し、 具象クラスは {@link
 *       #handle(Object)} だけ実装する。
 *   <li>Command: 入力 {@code C} は不変オブジェクト ({@code record} 推奨)。ログや再実行で扱いやすい。
 * </ul>
 *
 * @param <C> Command 型 (入力)
 * @param <R> Result 型 (出力)
 */
public abstract class TransactionalUseCase<C, R> {

    protected final UnitOfWork uow;

    protected TransactionalUseCase(UnitOfWork uow) {
        this.uow = Objects.requireNonNull(uow, "uow");
    }

    public final R execute(C command) {
        Objects.requireNonNull(command, "command");
        validate(command);
        return uow.execute(txMode(), () -> handle(command));
    }

    /** 既定は {@link Tx#REQUIRED}。読込専用なら {@link Tx#READ_ONLY} を返す override を行う。 */
    protected Tx txMode() {
        return Tx.REQUIRED;
    }

    /** 入力検証。失敗時はドメイン例外を投げる。既定は no-op。 */
    protected void validate(C command) {
        // 既定は何もしない
    }

    /** 業務本体。Tx の中で呼ばれる。 */
    protected abstract R handle(C command);
}
