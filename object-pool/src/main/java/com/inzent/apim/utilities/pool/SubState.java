package com.inzent.apim.utilities.pool;

/** Main-lock guarded lifecycle metadata. Actual entries belong to the Sub's own container. */
final class SubState<T> {
    final long id;
    final int initial;
    final SubPool<T> handle;
    boolean preparing = true, closed;
    int borrowed;
    long borrows, recycles;
    SubState(MainPool<T> main, long id, int initial) {
        this.id = id; this.initial = initial;
        handle = new SubPool<>(main, this);
    }
    SubPoolStats snapshot() {
        return new SubPoolStats(id, closed, initial, handle.container.managedCount(),
                handle.container.idleCount(), borrowed, 0, borrows, recycles);
    }
}
