package com.theater.shared.error;

/** 楽観ロック失敗 (UPDATE WHERE version = ? の影響行数が0)。呼出側で再試行するか伝搬。 */
public final class OptimisticLockException extends DomainException {

    private static final long serialVersionUID = 1L;

    public OptimisticLockException(String aggregate, String id) {
        super("Optimistic lock failed for " + aggregate + "(" + id + ")");
    }
}
