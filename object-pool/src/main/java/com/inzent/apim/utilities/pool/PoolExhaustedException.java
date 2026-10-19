package com.inzent.apim.utilities.pool;

/** No waiting is performed when a capacity limit is reached. */
public final class PoolExhaustedException extends IllegalStateException {
    public PoolExhaustedException(String message) { super(message); }
}
