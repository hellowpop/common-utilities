package com.inzent.apim.utilities.recycle;

import java.util.ArrayDeque;

/** Owner-thread-only facade; no local locks. HolderLease operations may run on workers. */
public class SubContainer<T> implements AutoCloseable {
    final MainContainer<T> main;
    final ArrayDeque<MainContainer.Token<T>> stack = new ArrayDeque<>();
    MainContainer.SubState<T> state;
    protected SubContainer(MainContainer<T> main) { this.main = java.util.Objects.requireNonNull(main); }
    protected void onBorrow() {}
    void checkOwner() {
        main.checkEntry();
        if (state == null || Thread.currentThread() != state.ownerThread)
            throw new IllegalStateException("Sub operations require the borrowing thread");
    }
    public HolderLease<T> borrowObjectHolder() { checkOwner(); return main.borrowHolder(this); }
    public boolean recycleObjectHolder(HolderLease<T> lease) {
        checkOwner(); main.requireActive(this);
        if (lease == null || lease.main != main || lease.owner != state)
            throw new IllegalArgumentException("Holder belongs to another Sub or Main");
        return lease.recycle();
    }
    public SubStats stats() { checkOwner(); return main.subStats(this); }
    public void touch() { checkOwner(); main.touch(this); }
    public PatrolResult patrol() { checkOwner(); return main.patrol(this); }
    @Override public void close() { checkOwner(); main.recycleContainer(this); stack.clear(); }
}
