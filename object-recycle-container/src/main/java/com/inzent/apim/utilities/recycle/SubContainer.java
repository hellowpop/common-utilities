package com.inzent.apim.utilities.recycle;

import java.util.ArrayDeque;
import java.lang.ref.WeakReference;

/**
 * Owner-thread-only facade; no local locks. HolderLease operations may run on workers.
 * Released handles retain identity and statistics, but not their Main or pooled payloads.
 */
public class SubContainer<T> implements AutoCloseable {
    private final WeakReference<MainContainer<T>> mainReference;
    private volatile MainContainer<T> liveMain;
    private volatile SubStats releasedStats;
    final ArrayDeque<MainContainer.Token> stack = new ArrayDeque<>();
    MainContainer.SubState<T> state;
    protected SubContainer(MainContainer<T> main) {
        liveMain = java.util.Objects.requireNonNull(main); mainReference = new WeakReference<>(main);
    }
    MainContainer<T> main() {
        MainContainer<T> main = liveMain;
        return main != null ? main : mainReference.get();
    }
    void detach(SubStats stats) { releasedStats = stats; liveMain = null; }
    private MainContainer<T> requireMain() {
        MainContainer<T> main = main();
        if (main == null) throw new IllegalStateException("Sub is released");
        return main;
    }
    protected void onBorrow() {}
    void checkOwner() {
        MainContainer<T> main = main();
        if (main != null) main.checkEntry();
        if (state == null || Thread.currentThread().threadId() != state.ownerThreadId)
            throw new IllegalStateException("Sub operations require the borrowing thread");
    }
    public HolderLease<T> borrowObjectHolder() { checkOwner(); return requireMain().borrowHolder(this); }
    public boolean recycleObjectHolder(HolderLease<T> lease) {
        checkOwner(); MainContainer<T> main = requireMain(); main.requireActive(this);
        if (lease == null || lease.main() != main || lease.ownerId != state.id)
            throw new IllegalArgumentException("Holder belongs to another Sub or Main");
        return lease.recycle();
    }
    public SubStats stats() {
        checkOwner(); MainContainer<T> main = main();
        return main != null ? main.subStats(this) : releasedStats;
    }
    public void touch() { checkOwner(); requireMain().touch(this); }
    public PatrolResult patrol() { checkOwner(); return requireMain().patrol(this); }
    @Override public void close() {
        checkOwner();
        try { MainContainer<T> main = main(); if (main != null) main.recycleContainer(this); }
        finally { stack.clear(); }
    }
}
