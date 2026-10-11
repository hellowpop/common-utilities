package com.inzent.apim.utilities.recycle;

import java.util.Objects;
import java.util.function.Consumer;

/** Fresh per-borrow handle. Never retain the mutable T outside a use callback. */
public final class HolderLease<T> implements AutoCloseable {
    final MainContainer<T> main;
    final MainContainer.Slot<T> slot;
    final MainContainer.SubState<T> owner;
    final long generation;
    HolderLease(MainContainer<T> main, MainContainer.Slot<T> slot, MainContainer.SubState<T> owner, long generation) {
        this.main = main; this.slot = slot; this.owner = owner; this.generation = generation;
    }
    public long id() { return slot.id; }
    public long generation() { return generation; }
    public void use(Consumer<? super T> action) { main.use(this, Objects.requireNonNull(action, "action")); }
    public void touch() { main.touch(this); }
    public boolean recycle() { return main.recycle(this); }
    @Override public void close() { recycle(); }
}
