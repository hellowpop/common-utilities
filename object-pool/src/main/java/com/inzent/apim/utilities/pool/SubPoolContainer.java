package com.inzent.apim.utilities.pool;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/** One Sub's actual entries and local idle LIFO. All access uses Main's coordination lock. */
final class SubPoolContainer<T> {
    private Map<Long, PooledEntry<T>> managed = new LinkedHashMap<>();
    private ArrayDeque<PooledEntry<T>> idle = new ArrayDeque<>();
    private int managedHighWater, idleHighWater;

    void register(PooledEntry<T> entry) {
        managed.put(entry.id, entry); managedHighWater = Math.max(managedHighWater, managed.size());
    }
    PooledEntry<T> borrowIdle() {
        PooledEntry<T> entry = idle.pollLast();
        if (MainPool.needsStorageCompaction(idleHighWater, idle.size())) {
            idle = new ArrayDeque<>(idle); idleHighWater = idle.size();
        }
        return entry;
    }
    void recycle(PooledEntry<T> entry) {
        idle.addLast(entry); idleHighWater = Math.max(idleHighWater, idle.size());
    }
    void release(PooledEntry<T> entry) {
        managed.remove(entry.id);
        if (MainPool.needsStorageCompaction(managedHighWater, managed.size())) {
            managed = new LinkedHashMap<>(managed); managedHighWater = managed.size();
        }
    }
    void releaseEmptyStorage() {
        if (!managed.isEmpty() || !idle.isEmpty())
            throw new IllegalStateException("Cannot discard a nonempty Sub container");
        managed = new LinkedHashMap<>(); idle = new ArrayDeque<>(0);
        managedHighWater = 0; idleHighWater = 0;
    }
    int managedCount() { return managed.size(); }
    int idleCount() { return idle.size(); }
    boolean isEmpty() { return managed.isEmpty(); }
}
