package com.inzent.apim.utilities.recycle;

/** An immediate capacity failure; borrowing never blocks waiting for a return. */
public final class PoolExhaustedException extends IllegalStateException {
    private static final long serialVersionUID = 1L;
    public PoolExhaustedException(String message) { super(message); }
}
