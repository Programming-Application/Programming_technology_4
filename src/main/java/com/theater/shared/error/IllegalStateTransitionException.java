package com.theater.shared.error;

/** 集約の状態遷移が不正 (例: CANCELED な Order を CONFIRM しようとした)。 */
public final class IllegalStateTransitionException extends DomainException {

    private static final long serialVersionUID = 1L;

    public IllegalStateTransitionException(String aggregate, String from, String to) {
        super("Illegal state transition on " + aggregate + ": " + from + " -> " + to);
    }
}
