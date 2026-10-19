package com.inzent.apim.utilities.pool;

import java.io.Closeable;
import java.io.IOException;

/** Per-borrow handle. The raw target must never be used after this handle is closed. */
public final class ObjectHolder<T> implements Closeable {
    private volatile MainPool<T> main;
    final Object poolIdentity;
    final long subId, entryId, generation;

    ObjectHolder(MainPool<T> main, long subId, long entryId, long generation) {
        this.main = main; poolIdentity = main.identity;
        this.subId = subId; this.entryId = entryId; this.generation = generation;
    }
    void detach() { main = null; }
    public T get() {
        MainPool<T> current = main;
        if (current == null) throw new IllegalStateException("Holder is recycled");
        return current.target(this);
    }
    @Override public void close() throws IOException {
        MainPool<T> current = main;
        if (current != null) current.onRecycleObject(this);
    }
}
