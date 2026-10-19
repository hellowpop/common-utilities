package com.inzent.apim.utilities.pool;

import java.util.ArrayDeque;

/** Main-lock guarded metadata; stores IDs rather than targets. */
final class SubState<T> {
    final long id;
    final int initial;
    final SubPool<T> handle;
    final ArrayDeque<Long> idle = new ArrayDeque<>();
    boolean preparing = true, closed;
    int borrowed;
    long borrows, recycles;
    SubState(MainPool<T> main, long id, int initial) {
        this.id = id; this.initial = initial;
        handle = new SubPool<>(main, this);
    }
    SubPoolStats snapshot() {
        return new SubPoolStats(id, closed, initial, idle.size() + borrowed,
                idle.size(), borrowed, 0, borrows, recycles);
    }
}
