package com.inzent.apim.utilities.pool;

import java.io.Closeable;
import java.io.IOException;
import java.util.Objects;

/** A fresh scope for every Main borrow. Closing allows existing holders to return late. */
public final class SubPool<T> implements Closeable {
    private volatile MainPool<T> main;
    private volatile SubPoolStats finalStats;
    final Object poolIdentity;
    final SubState<T> state;
    final SubPoolContainer<T> container = new SubPoolContainer<>();

    SubPool(MainPool<T> main, SubState<T> state) {
        this.main = main; this.state = state; poolIdentity = main.identity;
    }
    void detach(SubPoolStats snapshot) {
        container.releaseEmptyStorage(); finalStats = snapshot; main = null;
    }
    public ObjectHolder<T> borrowObjectHolder() throws IOException {
        MainPool<T> current = main;
        if (current == null) throw new IllegalStateException("Sub pool is closed");
        return current.onBorrowObject(state);
    }
    public void recycleObjectHolder(ObjectHolder<T> holder) throws IOException {
        Objects.requireNonNull(holder, "holder");
        if (holder.poolIdentity != poolIdentity || holder.subId != state.id)
            throw new IllegalArgumentException("Holder belongs to another Main/Sub pool");
        holder.close();
    }
    public SubPoolStats stats() {
        MainPool<T> current = main;
        return current == null ? finalStats : current.subStats(state);
    }
    @Override public void close() throws IOException {
        MainPool<T> current = main;
        if (current != null) current.recycleSubPool(this);
    }
}
