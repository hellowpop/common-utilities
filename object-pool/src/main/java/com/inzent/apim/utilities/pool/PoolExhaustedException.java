package com.inzent.apim.utilities.pool;

/** No waiting is performed when the active/preparing Sub pool count limit is reached. */
public final class PoolExhaustedException extends IllegalStateException {
    public PoolExhaustedException(String message) { super(message); }
}
