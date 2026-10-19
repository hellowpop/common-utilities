package com.inzent.apim.utilities.pool;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe hierarchy coordinator. User lifecycle hooks execute outside the state lock.
 * close stops lending immediately; outstanding targets are destroyed on holder return.
 */
public abstract class MainPool<T> implements Closeable {
    final Object identity = new Object();
    private final PoolConfig config;
    private final ReentrantLock lock = new ReentrantLock();
    private final ThreadLocal<Boolean> inHook = new ThreadLocal<>();
    private final Map<Long, PooledEntry<T>> entries = new LinkedHashMap<>();
    private final IdentityHashMap<T, PooledEntry<T>> targets = new IdentityHashMap<>();
    private final Map<Long, SubState<T>> subs = new LinkedHashMap<>();
    private final ArrayDeque<Long> idle = new ArrayDeque<>();
    private PoolState life = PoolState.NEW;
    private int reserved;
    private long nextEntry, nextSub, created, creationFailures, destroyAttempts, destroyFailures;
    private long subBorrows, subRecycles, holderBorrows, holderRecycles;

    protected MainPool(PoolConfig config) { this.config = Objects.requireNonNull(config, "config"); }
    protected abstract T generateTargetObject() throws Exception;
    protected abstract void destroyTargetObject(T target) throws Exception;

    private void checkEntry() {
        if (Boolean.TRUE.equals(inHook.get()))
            throw new IllegalStateException("Lifecycle hooks cannot reenter pool operations");
    }
    private void requireOpen(SubState<T> sub) {
        if (life != PoolState.OPEN || sub.closed)
            throw new IllegalStateException("Main or Sub pool is not open");
    }
    private void terminateIfDrained() {
        if (life == PoolState.CLOSING && reserved == 0 && entries.isEmpty()) life = PoolState.CLOSED;
    }

    public final void start() throws IOException {
        checkEntry();
        lock.lock();
        try {
            if (life == PoolState.OPEN) return;
            if (life != PoolState.NEW) throw new IllegalStateException("Pool cannot start: " + life);
            life = PoolState.STARTING;
        } finally { lock.unlock(); }
        try {
            for (int i = 0; i < config.initialSize(); i++) createInitial();
            lock.lock();
            try {
                if (life != PoolState.STARTING) throw new IOException("Pool startup cancelled");
                life = PoolState.OPEN;
            } finally { lock.unlock(); }
        } catch (Throwable failure) {
            try { close(); } catch (Throwable cleanup) { failure = merge(failure, cleanup); }
            throwFailure(failure);
        }
    }

    private void createInitial() throws IOException {
        lock.lock();
        try {
            if (life != PoolState.STARTING) throw new IOException("Pool startup cancelled");
            reserved++;
        } finally { lock.unlock(); }
        createReserved(null, true);
    }

    public final SubPool<T> borrowSubPool() throws IOException {
        checkEntry();
        SubState<T> sub;
        lock.lock();
        try {
            if (life != PoolState.OPEN) throw new IllegalStateException("Main pool is not open");
            long occupied = subs.values().stream().filter(s -> !s.closed).count();
            if (occupied >= config.maxSubPools()) throw new PoolExhaustedException("Sub pool limit reached");
            sub = new SubState<>(this, ++nextSub, config.initialObjectsPerSub());
            subs.put(sub.id, sub);
        } finally { lock.unlock(); }
        try {
            for (int i = 0; i < sub.initial; i++) obtain(sub, true);
            lock.lock();
            try {
                requireOpen(sub); sub.preparing = false; subBorrows++;
                return sub.handle;
            } finally { lock.unlock(); }
        } catch (Throwable failure) {
            try { closeSub(sub); } catch (Throwable cleanup) { failure = merge(failure, cleanup); }
            throwFailure(failure); throw new AssertionError("unreachable");
        }
    }

    final ObjectHolder<T> onBorrowObject(SubState<T> sub) throws IOException {
        checkEntry(); return obtain(sub, false);
    }

    private ObjectHolder<T> obtain(SubState<T> sub, boolean initial) throws IOException {
        lock.lock();
        try {
            requireOpen(sub);
            PooledEntry<T> entry = null;
            if (!initial && !sub.idle.isEmpty()) entry = entries.get(sub.idle.removeLast());
            if (entry == null && !idle.isEmpty()) entry = entries.get(idle.removeLast());
            if (entry == null && entries.size() + reserved >= config.maxSize()) {
                for (SubState<T> other : subs.values()) {
                    if (other != sub && !other.preparing && !other.idle.isEmpty()) {
                        entry = entries.get(other.idle.removeLast()); break;
                    }
                }
                if (entry == null) throw new PoolExhaustedException("Object limit reached");
            }
            if (entry != null) return assign(entry, sub, initial);
            reserved++;
        } finally { lock.unlock(); }
        return createReserved(sub, initial);
    }

    // Lock held: existing entry has been removed from its previous idle queue.
    private ObjectHolder<T> assign(PooledEntry<T> entry, SubState<T> sub, boolean initial) {
        entry.owner = sub;
        if (initial) {
            entry.state = PooledEntry.State.SUB_IDLE;
            sub.idle.addLast(entry.id); return null;
        }
        entry.state = PooledEntry.State.BORROWED;
        entry.holder = new ObjectHolder<>(this, sub.id, entry.id, ++entry.generation);
        sub.borrowed++; sub.borrows++; holderBorrows++;
        return entry.holder;
    }

    private ObjectHolder<T> createReserved(SubState<T> sub, boolean initial) throws IOException {
        T value = null;
        Throwable failure = null;
        try {
            inHook.set(true);
            value = generateTargetObject();
            if (value == null) throw new IOException("Generator returned null");
        } catch (Throwable ex) { failure = ex; }
        finally { inHook.remove(); }
        PooledEntry<T> cleanup = null;
        ObjectHolder<T> result = null;
        lock.lock();
        try {
            reserved--;
            if (failure == null && targets.containsKey(value))
                failure = new IOException("Generator returned an already managed target instance");
            if (failure != null) { creationFailures++; }
            else {
                var entry = new PooledEntry<>(this, ++nextEntry, value);
                entries.put(entry.id, entry); targets.put(value, entry); created++;
                boolean allowed = sub == null ? life == PoolState.STARTING
                        : life == PoolState.OPEN && !sub.closed;
                if (!allowed) {
                    cleanup = markDestroy(entry);
                    failure = sub == null ? new IOException("Pool startup cancelled")
                            : new IllegalStateException("Pool closed during object creation");
                } else if (sub == null) idle.addLast(entry.id);
                else result = assign(entry, sub, initial);
            }
            terminateIfDrained();
        } finally { lock.unlock(); }
        if (cleanup != null) {
            try { destroyAll(List.of(cleanup)); }
            catch (Throwable ex) { failure = merge(failure, ex); }
        }
        throwFailure(failure); return result;
    }

    final T target(ObjectHolder<T> holder) {
        checkEntry(); lock.lock();
        try {
            var entry = entries.get(holder.entryId);
            if (life != PoolState.OPEN || entry == null || entry.holder != holder
                    || entry.generation != holder.generation || entry.state != PooledEntry.State.BORROWED)
                throw new IllegalStateException("Holder is recycled or Main pool is closing");
            return entry.value;
        } finally { lock.unlock(); }
    }

    final void onRecycleObject(ObjectHolder<T> holder) throws IOException {
        checkEntry(); PooledEntry<T> cleanup = null;
        lock.lock();
        try {
            var entry = entries.get(holder.entryId);
            if (entry == null || entry.holder != holder || entry.generation != holder.generation) return;
            SubState<T> sub = entry.owner;
            entry.holder = null; holder.detach();
            sub.borrowed--; sub.recycles++; holderRecycles++;
            if (life != PoolState.OPEN) cleanup = markDestroy(entry);
            else if (!sub.closed && sub.idle.size() < config.maxCachedPerSub()) {
                entry.state = PooledEntry.State.SUB_IDLE; sub.idle.addLast(entry.id);
            } else {
                entry.owner = null; entry.state = PooledEntry.State.MAIN_IDLE; idle.addLast(entry.id);
            }
            detachIfDone(sub);
        } finally { lock.unlock(); }
        if (cleanup != null) destroyAll(List.of(cleanup));
    }

    public final void recycleSubPool(SubPool<T> sub) throws IOException {
        checkEntry(); Objects.requireNonNull(sub, "sub");
        if (sub.poolIdentity != identity) throw new IllegalArgumentException("Sub belongs to another Main pool");
        closeSub(sub.state);
    }

    private void closeSub(SubState<T> sub) throws IOException {
        List<PooledEntry<T>> cleanup = new ArrayList<>();
        List<SubPoolStats> warnings = new ArrayList<>();
        lock.lock();
        try { closeSubLocked(sub, cleanup, warnings); }
        finally { lock.unlock(); }
        finishClose(cleanup, warnings);
    }

    private void closeSubLocked(SubState<T> sub, List<PooledEntry<T>> cleanup,
                                List<SubPoolStats> warnings) {
        if (sub.closed) return;
        sub.closed = true;
        if (!sub.preparing) subRecycles++;
        sub.preparing = false;
        while (!sub.idle.isEmpty()) {
            var entry = entries.get(sub.idle.removeLast());
            if (life == PoolState.OPEN) {
                entry.owner = null; entry.state = PooledEntry.State.MAIN_IDLE; idle.addLast(entry.id);
            } else cleanup.add(markDestroy(entry));
        }
        if (sub.borrowed != 0) warnings.add(sub.snapshot());
        detachIfDone(sub);
    }

    // Cleanup precedes arbitrary logger code. Warning snapshots retain no pool or target.
    private void finishClose(List<PooledEntry<T>> cleanup, List<SubPoolStats> warnings) throws IOException {
        Throwable failure = null;
        try { destroyAll(cleanup); }
        catch (Throwable ex) { failure = ex; }
        for (SubPoolStats warning : warnings) {
            try {
                System.getLogger(MainPool.class.getName()).log(System.Logger.Level.WARNING,
                        "Sub pool {0} closed with {1} outstanding holders", warning.id(), warning.borrowedCount());
            } catch (Throwable ex) { failure = merge(failure, ex); }
        }
        throwFailure(failure);
    }

    private void detachIfDone(SubState<T> sub) {
        if (sub.closed && sub.borrowed == 0 && sub.idle.isEmpty()) {
            sub.handle.detach(sub.snapshot()); subs.remove(sub.id);
        }
    }

    private PooledEntry<T> markDestroy(PooledEntry<T> entry) {
        entry.owner = null; entry.state = PooledEntry.State.DESTROYING; destroyAttempts++;
        return entry;
    }

    final void runDestroyHook(T value) throws IOException {
        try {
            inHook.set(true); destroyTargetObject(value);
        } catch (Throwable failure) { throwFailure(failure); }
        finally { inHook.remove(); }
    }

    private void destroyAll(List<PooledEntry<T>> cleanup) throws IOException {
        Throwable failure = null;
        for (PooledEntry<T> entry : cleanup) {
            T value = entry.value;
            boolean failed = false;
            try { entry.close(); }
            catch (Throwable ex) { failed = true; failure = merge(failure, ex); }
            finally {
                lock.lock();
                try {
                    if (failed) destroyFailures++;
                    targets.remove(value); entries.remove(entry.id); terminateIfDrained();
                } finally { lock.unlock(); }
            }
        }
        throwFailure(failure);
    }

    public final PoolStats stats() {
        lock.lock();
        try {
            int local = 0, active = 0, preparing = 0, closed = 0, borrowed = 0, destroying = 0;
            for (SubState<T> sub : subs.values()) {
                local += sub.idle.size();
                if (sub.closed) closed++; else if (sub.preparing) preparing++; else active++;
            }
            for (PooledEntry<T> entry : entries.values()) {
                if (entry.state == PooledEntry.State.BORROWED) borrowed++;
                if (entry.state == PooledEntry.State.DESTROYING) destroying++;
            }
            return new PoolStats(life, entries.size(), idle.size(), active, idle.size() + local,
                    preparing, closed, borrowed, 0, destroying, reserved, created, creationFailures,
                    destroyAttempts, destroyFailures, subBorrows, subRecycles, holderBorrows, holderRecycles);
        } finally { lock.unlock(); }
    }

    final SubPoolStats subStats(SubState<T> sub) {
        lock.lock(); try { return sub.snapshot(); } finally { lock.unlock(); }
    }
    public final boolean isTerminated() { return stats().state() == PoolState.CLOSED; }

    @Override public final void close() throws IOException {
        checkEntry(); List<PooledEntry<T>> cleanup = new ArrayList<>();
        List<SubPoolStats> warnings = new ArrayList<>();
        lock.lock();
        try {
            if (life == PoolState.CLOSING || life == PoolState.CLOSED) return;
            life = PoolState.CLOSING;
            for (SubState<T> sub : new ArrayList<>(subs.values())) closeSubLocked(sub, cleanup, warnings);
            while (!idle.isEmpty()) cleanup.add(markDestroy(entries.get(idle.removeLast())));
            terminateIfDrained();
        } finally { lock.unlock(); }
        finishClose(cleanup, warnings);
    }

    static Throwable merge(Throwable first, Throwable next) {
        if (first == null) return next;
        if (first == next) return first;
        boolean fatal = next instanceof VirtualMachineError || next instanceof ThreadDeath;
        boolean firstFatal = first instanceof VirtualMachineError || first instanceof ThreadDeath;
        if ((fatal && !firstFatal) || (next instanceof Error && !(first instanceof Error))) {
            next.addSuppressed(first); return next;
        }
        first.addSuppressed(next); return first;
    }
    static void throwFailure(Throwable failure) throws IOException {
        if (failure == null) return;
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof IOException io) throw io;
        throw new IOException("Pool lifecycle hook failed", failure);
    }
}
