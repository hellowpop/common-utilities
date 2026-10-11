package com.inzent.apim.utilities.recycle;

/** A factory failure distinct from pool exhaustion, preserving the original cause. */
public final class ObjectCreationException extends IllegalStateException {
    private static final long serialVersionUID = 1L;
    public ObjectCreationException(Throwable cause) { super("Object creation failed", cause); }
}
