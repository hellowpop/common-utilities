package com.inzent.apim.utilities.recycle;

import java.util.Objects;

/** The actual reusable object, exposed only to Main's lifecycle hooks. */
public final class ObjectHolder<T> {
    private final T value;
    public ObjectHolder(T value) { this.value = Objects.requireNonNull(value, "value"); }
    public T value() { return value; }
}
