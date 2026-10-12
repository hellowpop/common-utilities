package com.inzent.apim.utilities.recycle;

import java.util.Objects;
import java.lang.ref.WeakReference;
import java.util.function.Consumer;

/**
 * Fresh per-borrow handle. Never retain the mutable T outside a use callback.
 * After return or revocation this handle no longer strongly retains its Main or payload.
 */
public final class HolderLease<T> implements AutoCloseable {
    private final WeakReference<MainContainer<T>> mainReference;
    private volatile MainContainer<T> liveMain;
    final long slotId, ownerId;
    final long generation;
    HolderLease(MainContainer<T> main, MainContainer.Slot<T> slot, MainContainer.SubState<T> owner, long generation) {
        mainReference = new WeakReference<>(main); liveMain = main;
        slotId = slot.id; ownerId = owner.id; this.generation = generation;
    }
    MainContainer<T> main() {
        MainContainer<T> main = liveMain;
        return main != null ? main : mainReference.get();
    }
    void detach() { liveMain = null; }
    private MainContainer<T> requireMain() {
        MainContainer<T> main = main();
        if (main == null) throw new IllegalStateException("Holder lease is recycled or expired");
        return main;
    }
    public long id() { return slotId; }
    public long generation() { return generation; }
    public void use(Consumer<? super T> action) {
        Objects.requireNonNull(action, "action"); requireMain().use(this, action);
    }
    public void touch() { requireMain().touch(this); }
    public boolean recycle() { MainContainer<T> main = main(); return main != null && main.recycle(this); }
    @Override public void close() { recycle(); }
}
