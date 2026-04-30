package com.theater.shared.error;

/** 集約が見つからない。 */
public final class NotFoundException extends DomainException {

    private static final long serialVersionUID = 1L;

    public NotFoundException(String aggregate, String id) {
        super(aggregate + " not found: " + id);
    }
}
