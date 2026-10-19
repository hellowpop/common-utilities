package com.inzent.apim.utilities.pool;

import java.io.Closeable;
import java.io.IOException;

/** Physical resource. Only the Main that claims DESTROYING may invoke close. */
final class PooledEntry<T> implements Closeable {
    enum State { MAIN_IDLE, SUB_IDLE, BORROWED, DESTROYING }
    final long id;
    T value;
    MainPool<T> main;
    SubState<T> owner;
    ObjectHolder<T> holder;
    State state = State.MAIN_IDLE;
    long generation;

    PooledEntry(MainPool<T> main, long id, T value) {
        this.main = main; this.id = id; this.value = value;
    }
    @Override public void close() throws IOException {
        try { main.runDestroyHook(value); }
        finally { value = null; main = null; }
    }
}
