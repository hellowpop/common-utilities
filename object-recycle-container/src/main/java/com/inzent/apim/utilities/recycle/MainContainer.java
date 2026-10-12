package com.inzent.apim.utilities.recycle;

import java.time.Duration;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;

/**
 * Thread-safe lifecycle coordinator. Call start after subclass construction.
 * Lifecycle hooks run outside the metadata lock, must terminate, and must not
 * reenter this Main. Different holders' hooks may run concurrently.
 * Cleanup errors are propagated after best-effort disposal of the claimed batch.
 * Main must be explicitly closed, especially when automatic patrol is enabled.
 */
public abstract class MainContainer<T> implements AutoCloseable {
    private enum Life { NEW, STARTING, OPEN, CLOSED }
    enum SlotLife { IDLE, LOCAL, RETURNED, BORROWED, RECYCLING, QUARANTINED, DESTROYING }
    enum SubLife { PREPARING, ACTIVE, REVOKED, RELEASED }

    static final class Slot<T> {
        final long id;
        final ObjectHolder<T> holder;
        SlotLife life;
        SubState<T> owner;
        long generation, borrowedAt, lastUsedAt, idleAt;
        boolean inUse, cleaning, destroyRequested, acceptedReturn;
        HolderLease<T> lease;
        List<StackTraceElement> borrowStack = List.of();
        Slot(long id, ObjectHolder<T> holder) { this.id = id; this.holder = holder; }
    }
    static final class SubState<T> {
        final long id;
        final long ownerThreadId;
        final SubContainer<T> handle;
        final long borrowedAt;
        final List<StackTraceElement> borrowStack;
        final ArrayDeque<Token> returns = new ArrayDeque<>();
        long lastUsedAt, borrows, recycles, forced, outstanding;
        int creating;
        boolean force;
        SubLife life = SubLife.PREPARING;
        SubState(long id, SubContainer<T> handle, long now, List<StackTraceElement> borrowStack) {
            this.id = id; this.handle = handle; ownerThreadId = Thread.currentThread().threadId();
            borrowedAt = lastUsedAt = now; this.borrowStack = borrowStack;
        }
    }
    record Token(long slotId, long generation) {}

    private final ContainerConfig config;
    private final LeakListener listener;
    private final LongSupplier clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<Long, Slot<T>> slots = new LinkedHashMap<>();
    private final Map<Long, SubState<T>> subs = new LinkedHashMap<>();
    private final ArrayDeque<Slot<T>> idle = new ArrayDeque<>();
    private final IdentityHashMap<ObjectHolder<T>, Boolean> holders = new IdentityHashMap<>();
    private final IdentityHashMap<T, Boolean> values = new IdentityHashMap<>();
    private final ThreadLocal<Boolean> inHook = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<Boolean> maintaining = ThreadLocal.withInitial(() -> false);
    private final AtomicBoolean scanning = new AtomicBoolean();
    private final System.Logger logger = System.getLogger(MainContainer.class.getName());
    private Life life = Life.NEW;
    private int creating;
    private long sequence, destroyedCount, failureCount;
    private ScheduledExecutorService scheduler;

    protected MainContainer(ContainerConfig config) { this(config, null, System::nanoTime); }
    protected MainContainer(ContainerConfig config, LeakListener listener) { this(config, listener, System::nanoTime); }
    /** The supplied clock must be monotonic, thread-safe, non-blocking and non-throwing. */
    protected MainContainer(ContainerConfig config, LeakListener listener, LongSupplier clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.listener = listener == null ? event -> event.forceClose() : listener;
        this.clock = Objects.requireNonNull(clock, "clock");
    }
    protected abstract ObjectHolder<T> createNewHolder();
    protected abstract boolean isRecycleable(ObjectHolder<T> holder);
    protected abstract void onRecycle(ObjectHolder<T> holder);
    protected abstract void onDestroy(ObjectHolder<T> holder);
    /** Extension point for owner-thread onBorrow initialization. Return a fresh Sub each time. */
    protected SubContainer<T> createSubContainer() { return new SubContainer<>(this); }

    void checkEntry() {
        if (inHook.get()) throw new IllegalStateException("Lifecycle hook reentry into the same Main is forbidden");
    }
    private <R> R locked(Supplier<R> operation) {
        lock.lock();
        try { return operation.get(); } finally { lock.unlock(); }
    }
    private <R> R hook(Supplier<R> operation) {
        inHook.set(true);
        try { return operation.get(); } finally { inHook.remove(); }
    }
    private void hookRun(Runnable operation) { hook(() -> { operation.run(); return null; }); }
    private long now() { return clock.getAsLong(); }
    private long nextId() { return sequence = Math.incrementExact(sequence); }
    private List<StackTraceElement> capture() {
        return config.captureBorrowStack() ? List.of(Thread.currentThread().getStackTrace()) : List.of();
    }
    private void requireOpen() {
        if (life != Life.OPEN) throw new IllegalStateException("Main is " + life);
    }
    private void requireActive(SubState<T> sub) {
        requireOpen();
        if (sub.life != SubLife.ACTIVE) throw new IllegalStateException("Sub is " + sub.life);
    }
    void requireActive(SubContainer<T> sub) { locked(() -> { requireActive(sub.state); return null; }); }

    public final void start() {
        checkEntry();
        boolean begin = locked(() -> {
            if (life == Life.OPEN) return false;
            if (life != Life.NEW) throw new IllegalStateException("Cannot start Main in " + life);
            life = Life.STARTING; return true;
        });
        if (!begin) return;
        try {
            allocate(null, config.initial(), true);
            locked(() -> {
                if (life != Life.STARTING) throw new IllegalStateException("Main closed while starting");
                life = Life.OPEN;
                if (config.automaticPatrol()) {
                    scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
                        var thread = new Thread(task, "object-recycle-patrol"); thread.setDaemon(true); return thread;
                    });
                    scheduler.scheduleWithFixedDelay(this::automaticScan, config.patrolInterval().toNanos(),
                            config.patrolInterval().toNanos(), TimeUnit.NANOSECONDS);
                }
                return null;
            });
        } catch (RuntimeException | Error failure) { preservingFailure(failure, this::close); throw failure; }
    }

    public final SubContainer<T> borrowContainer() {
        checkEntry();
        locked(() -> { requireOpen(); return null; });
        replenish();
        SubContainer<T> handle = hook(this::createSubContainer);
        if (handle == null || handle.main() != this || handle.state != null)
            throw new IllegalArgumentException("createSubContainer must return a fresh Sub belonging to this Main");
        SubState<T> sub = locked(() -> {
            requireOpen();
            if (subs.size() >= config.maxSubContainers()) throw new PoolExhaustedException("Sub capacity reached");
            var state = new SubState<>(nextId(), handle, now(), capture());
            handle.state = state; subs.put(state.id, state); return state;
        });
        try {
            List<Token> tokens = allocate(sub, config.initialObjectsPerSub(), false);
            tokens.forEach(handle.stack::push);
            hookRun(handle::onBorrow);
            locked(() -> {
                requireOpen();
                if (sub.life != SubLife.PREPARING) throw new IllegalStateException("Sub revoked during initialization");
                sub.life = SubLife.ACTIVE; return null;
            });
            return handle;
        } catch (RuntimeException | Error failure) {
            List<Slot<T>> cleanup = locked(() -> revoke(sub, true));
            handle.stack.clear(); preservingFailure(failure, () -> runCleanup(cleanup, true)); throw failure;
        }
    }

    /** Assign one additional available holder to the specified owner's LIFO stack. */
    public final void newObject(SubContainer<T> sub) {
        checkEntry(); checkOwned(sub); sub.checkOwner(); requireActive(sub);
        List<Token> tokens = allocate(sub.state, 1, false);
        tokens.forEach(sub.stack::push);
    }

    private void checkOwned(SubContainer<T> sub) {
        if (sub == null || sub.main() != this || sub.state == null)
            throw new IllegalArgumentException("Sub belongs to another Main or is uninitialized");
    }

    /** Reserve all capacity before running any factory. Partial allocations are rolled back. */
    private List<Token> allocate(SubState<T> owner, int count, boolean startup) {
        var selected = new ArrayList<Token>();
        var fresh = new ArrayList<Slot<T>>();
        int reserved = locked(() -> {
            checkAllocation(owner, startup);
            int available = owner == null ? 0 : Math.min(count, idle.size());
            int missing = count - available;
            if (slots.size() + creating + missing > config.max()) throw new PoolExhaustedException("Object capacity reached");
            for (int i = 0; i < available; i++) {
                Slot<T> slot = idle.removeFirst(); slot.life = SlotLife.LOCAL; slot.owner = owner;
                selected.add(new Token(slot.id, slot.generation));
            }
            creating += missing; if (owner != null) owner.creating += missing;
            return missing;
        });
        int remaining = reserved;
        try {
            for (int i = 0; i < reserved; i++) {
                ObjectHolder<T> holder;
                try { holder = hook(() -> Objects.requireNonNull(createNewHolder(), "createNewHolder returned null")); }
                catch (RuntimeException failure) { throw new ObjectCreationException(failure); }
                Slot<T> slot = locked(() -> {
                    if (holders.containsKey(holder) || values.containsKey(holder.value()))
                        throw new ObjectCreationException(new IllegalArgumentException("Factory returned a shared Holder or value"));
                    var created = new Slot<T>(nextId(), holder);
                    created.owner = owner; created.idleAt = now();
                    created.life = owner == null ? SlotLife.IDLE : SlotLife.LOCAL;
                    slots.put(created.id, created); holders.put(holder, true); values.put(holder.value(), true);
                    creating--; if (owner != null) owner.creating--;
                    if (owner == null) idle.addLast(created);
                    return created;
                });
                remaining--; selected.add(new Token(slot.id, 0)); fresh.add(slot);
                locked(() -> { checkAllocation(owner, startup); return null; });
            }
            return locked(() -> {
                checkAllocation(owner, startup);
                return List.copyOf(selected);
            });
        } catch (RuntimeException | Error failure) {
            final int uncreated = remaining;
            List<Slot<T>> cleanup = locked(() -> {
                creating -= uncreated; if (owner != null) owner.creating -= uncreated;
                var actions = new ArrayList<Slot<T>>();
                for (Token token : selected) {
                    Slot<T> slot = slots.get(token.slotId());
                    // Registry revocation can return this slot and another Sub can borrow it
                    // before the allocation resumes. Rollback owns only its original assignment.
                    if (slot == null || slot.owner != owner || slot.generation != token.generation()) continue;
                    if (!fresh.contains(slot) && life == Life.OPEN && (owner == null || !owner.force)
                            && slot.life == SlotLife.LOCAL) {
                        slot.owner = null; slot.life = SlotLife.IDLE; slot.idleAt = now(); idle.addLast(slot);
                    } else {
                        markDestroy(slot); if (claimCleanup(slot)) actions.add(slot);
                    }
                }
                releaseSub(owner); return actions;
            });
            preservingFailure(failure, () -> runCleanup(cleanup, false)); throw failure;
        }
    }
    private void checkAllocation(SubState<T> owner, boolean startup) {
        if (startup) {
            if (life != Life.STARTING) throw new IllegalStateException("Main no longer starting");
        } else requireOpen();
        if (owner != null && owner.life != SubLife.ACTIVE && owner.life != SubLife.PREPARING)
            throw new IllegalStateException("Sub revoked during allocation");
    }

    HolderLease<T> borrowHolder(SubContainer<T> sub) {
        requireActive(sub);
        replenish();
        drainReturns(sub);
        while (true) {
            if (sub.stack.isEmpty()) newObject(sub);
            Token token = sub.stack.pop();
            var cleanup = new ArrayList<Slot<T>>();
            HolderLease<T> lease = locked(() -> {
                requireActive(sub.state);
                Slot<T> slot = slots.get(token.slotId());
                if (slot == null || slot.owner != sub.state || slot.life != SlotLife.LOCAL
                        || slot.generation != token.generation()) return null;
                if (slot.generation == Long.MAX_VALUE) {
                    markDestroy(slot); if (claimCleanup(slot)) cleanup.add(slot); return null;
                }
                slot.generation++; slot.life = SlotLife.BORROWED; slot.destroyRequested = slot.acceptedReturn = false;
                slot.borrowedAt = slot.lastUsedAt = sub.state.lastUsedAt = now(); slot.borrowStack = capture();
                var handle = new HolderLease<>(this, slot, sub.state, slot.generation); slot.lease = handle;
                sub.state.borrows++; sub.state.outstanding++; return handle;
            });
            runCleanup(cleanup, true);
            if (lease != null) return lease;
        }
    }
    private void drainReturns(SubContainer<T> sub) {
        locked(() -> {
            requireActive(sub.state);
            while (!sub.state.returns.isEmpty()) {
                Token token = sub.state.returns.removeFirst(); Slot<T> slot = slots.get(token.slotId());
                if (slot != null && slot.owner == sub.state && slot.life == SlotLife.RETURNED && slot.generation == token.generation()) {
                    slot.life = SlotLife.LOCAL; sub.stack.push(token);
                }
            }
            return null;
        });
    }

    private boolean active(HolderLease<T> lease) {
        Slot<T> slot = slots.get(lease.slotId);
        return life == Life.OPEN && slot != null && slot.lease == lease && slot.generation == lease.generation
                && slot.life == SlotLife.BORROWED && slot.owner != null
                && slot.owner.id == lease.ownerId && slot.owner.life == SubLife.ACTIVE;
    }
    private void requireActive(HolderLease<T> lease) {
        if (!active(lease)) throw new IllegalStateException("Holder lease is recycled or expired");
    }
    void use(HolderLease<T> lease, Consumer<? super T> action) {
        checkEntry();
        Slot<T> slot = locked(() -> {
            requireActive(lease);
            Slot<T> current = slots.get(lease.slotId);
            if (current.inUse) throw new IllegalStateException("Concurrent or nested use is forbidden");
            current.inUse = true; current.lastUsedAt = current.owner.lastUsedAt = now(); return current;
        });
        Throwable useFailure = null;
        try { action.accept(slot.holder.value()); }
        catch (Throwable failure) { useFailure = failure; throw failure; }
        finally {
            final boolean completed = useFailure == null;
            boolean cleanup = locked(() -> {
                slot.inUse = false;
                if (!completed) markDestroy(slot);
                if (active(lease)) slot.lastUsedAt = slot.owner.lastUsedAt = now();
                return claimCleanup(slot);
            });
            if (cleanup) {
                if (useFailure == null) runCleanup(List.of(slot), true);
                else preservingFailure(useFailure, () -> runCleanup(List.of(slot), true));
            }
        }
    }
    void touch(HolderLease<T> lease) {
        checkEntry(); locked(() -> {
            requireActive(lease); Slot<T> slot = slots.get(lease.slotId);
            slot.lastUsedAt = slot.owner.lastUsedAt = now(); return null;
        });
    }
    void touch(SubContainer<T> sub) {
        locked(() -> { requireActive(sub.state); sub.state.lastUsedAt = now(); return null; });
    }
    boolean recycle(HolderLease<T> lease) {
        checkEntry();
        var cleanup = new ArrayList<Slot<T>>();
        boolean accepted = locked(() -> {
            if (!active(lease)) return false;
            Slot<T> slot = slots.get(lease.slotId); SubState<T> owner = slot.owner;
            slot.life = SlotLife.RECYCLING; slot.acceptedReturn = true;
            lease.detach(); slot.borrowStack = List.of();
            owner.recycles++; owner.outstanding--; owner.lastUsedAt = now();
            if (claimCleanup(slot)) cleanup.add(slot); return true;
        });
        runCleanup(cleanup, true); return accepted;
    }

    public final boolean recycleContainer(SubContainer<T> sub) {
        checkEntry(); checkOwned(sub);
        var cleanup = new ArrayList<Slot<T>>();
        SubStats snapshot = locked(() -> {
            if (sub.state.life == SubLife.PREPARING) throw new IllegalStateException("Sub is initializing");
            if (sub.state.life != SubLife.ACTIVE) return null;
            var stats = subSnapshot(sub.state); cleanup.addAll(revoke(sub.state, false)); return stats;
        });
        if (snapshot == null) return false;
        warnMismatch(snapshot); runCleanup(cleanup, true); return true;
    }

    /** Lock required. Registry revocation never reads or changes the owner's stack. */
    private List<Slot<T>> revoke(SubState<T> sub, boolean force) {
        var cleanup = new ArrayList<Slot<T>>();
        if (sub.life == SubLife.RELEASED) return cleanup;
        sub.force |= force; sub.life = SubLife.REVOKED; sub.returns.clear();
        for (Slot<T> slot : slots.values()) {
            if (slot.owner != sub) continue;
            if (!sub.force && life == Life.OPEN && (slot.life == SlotLife.LOCAL || slot.life == SlotLife.RETURNED)) {
                slot.owner = null; slot.life = SlotLife.IDLE; slot.idleAt = now(); slot.lease = null; idle.addLast(slot);
            } else {
                if (sub.force || life == Life.CLOSED || slot.life == SlotLife.BORROWED) markDestroy(slot);
                if (claimCleanup(slot)) cleanup.add(slot);
            }
        }
        releaseSub(sub); return cleanup;
    }
    private void markDestroy(Slot<T> slot) {
        if (!slots.containsKey(slot.id)) return;
        if (slot.life == SlotLife.BORROWED) { slot.owner.forced++; slot.owner.outstanding--; }
        idle.remove(slot);
        slot.destroyRequested = true;
        if (slot.lease != null) slot.lease.detach();
        slot.borrowStack = List.of();
        if (slot.life != SlotLife.DESTROYING) slot.life = SlotLife.QUARANTINED;
    }
    private boolean claimCleanup(Slot<T> slot) {
        if (!slot.inUse && !slot.cleaning && slots.containsKey(slot.id)
                && (slot.life == SlotLife.RECYCLING || slot.life == SlotLife.QUARANTINED)) {
            slot.cleaning = true; return true;
        }
        return false;
    }
    private void runCleanup(List<Slot<T>> actions, boolean maintain) {
        Error failure = null;
        for (Slot<T> slot : actions) {
            try { cleanup(slot); }
            catch (Error error) { failure = combine(failure, error); }
        }
        try { if (maintain && !actions.isEmpty()) replenish(); }
        catch (Error error) { failure = combine(failure, error); }
        if (failure != null) throw failure;
    }
    private static Error combine(Error first, Error next) {
        if (first == null) return next;
        if (isFatal(next) && !isFatal(first)) { next.addSuppressed(first); return next; }
        if (first != next) first.addSuppressed(next);
        return first;
    }
    /** Keep the originating failure, unless cleanup discovers a fatal VM failure. */
    private static void preservingFailure(Throwable original, Runnable cleanup) {
        try { cleanup.run(); }
        catch (RuntimeException | Error failure) {
            if (failure == original) return;
            if (isFatal(failure) && !isFatal(original)) {
                failure.addSuppressed(original); rethrowFatal(failure);
            }
            original.addSuppressed(failure);
        }
    }
    private void cleanup(Slot<T> slot) {
        Error error = null;
        boolean destroy = locked(() -> slot.destroyRequested);
        if (!destroy) {
            try {
                destroy = !hook(() -> isRecycleable(slot.holder));
                if (!destroy) hookRun(() -> onRecycle(slot.holder));
            } catch (RuntimeException | Error failure) {
                destroy = true;
                if (failure instanceof Error fatal) error = fatal;
                diagnose("Recycle hook failed", failure);
            }
        }
        final boolean failedOrRejected = destroy;
        boolean mustDestroy = locked(() -> {
            SubState<T> owner = slot.owner;
            if (failedOrRejected || slot.destroyRequested || life != Life.OPEN || (owner != null && owner.force)) {
                slot.destroyRequested = true; slot.life = SlotLife.DESTROYING; return true;
            }
            slot.cleaning = false; slot.lease = null;
            if (owner != null && owner.life == SubLife.ACTIVE) {
                Token token = new Token(slot.id, slot.generation);
                if (Thread.currentThread().threadId() == owner.ownerThreadId) {
                    slot.life = SlotLife.LOCAL; owner.handle.stack.push(token);
                } else { slot.life = SlotLife.RETURNED; owner.returns.addLast(token); }
            } else {
                slot.owner = null; slot.life = SlotLife.IDLE; slot.idleAt = now(); idle.addLast(slot); releaseSub(owner);
            }
            return false;
        });
        if (!mustDestroy) return;
        try { hookRun(() -> onDestroy(slot.holder)); }
        catch (RuntimeException | Error failure) {
            if (failure instanceof Error fatal) error = combine(error, fatal);
            diagnose("Destroy hook failed; resource release is unconfirmed", failure);
        }
        finally {
            locked(() -> {
                SubState<T> owner = slot.owner;
                slots.remove(slot.id); holders.remove(slot.holder); values.remove(slot.holder.value());
                slot.owner = null; slot.lease = null; slot.cleaning = false; destroyedCount++; releaseSub(owner); return null;
            });
        }
        if (error != null) throw error;
    }
    private void releaseSub(SubState<T> sub) {
        if (sub == null || sub.life != SubLife.REVOKED || sub.creating != 0) return;
        if (slots.values().stream().noneMatch(slot -> slot.owner == sub)) {
            sub.life = SubLife.RELEASED; subs.remove(sub.id);
            sub.handle.detach(subSnapshot(sub));
        }
    }
    private void replenish() {
        if (maintaining.get()) return;
        maintaining.set(true);
        try {
            int missing = locked(() -> life == Life.OPEN ? Math.max(0, config.min() - slots.size() - creating) : 0);
            if (missing > 0) {
                try { allocate(null, missing, false); }
                catch (RuntimeException failure) { diagnose("Minimum capacity replenishment failed", failure); }
            }
        } finally { maintaining.remove(); }
    }
    private void diagnose(String message, Throwable failure) {
        locked(() -> { failureCount++; return null; }); logger.log(System.Logger.Level.WARNING, message, failure);
    }
    private void warnMismatch(SubStats stats) {
        if (stats.borrowCount() != stats.recycleCount()) logger.log(System.Logger.Level.WARNING,
                "Sub id={0}, generation=1: borrow={1}, recycle={2}, difference={3}, forced={4}, outstanding={5}, pending={6}",
                stats.id(), stats.borrowCount(), stats.recycleCount(), stats.borrowCount() - stats.recycleCount(),
                stats.forcedCloseCount(), stats.outstandingCount(), stats.pendingCleanupCount());
    }

    public final ContainerStats stats() {
        checkEntry();
        return locked(() -> {
            int[] counts = new int[SlotLife.values().length];
            for (Slot<T> slot : slots.values()) counts[slot.life.ordinal()]++;
            int active = 0, revoked = 0;
            for (SubState<T> sub : subs.values()) {
                if (sub.life == SubLife.REVOKED) revoked++; else active++;
            }
            return new ContainerStats(life.name(), config.initial(), config.min(), config.max(), slots.size(), creating,
                    counts[0], counts[1], counts[2], counts[3], counts[4], counts[5], counts[6], active, revoked,
                    destroyedCount, failureCount);
        });
    }
    SubStats subStats(SubContainer<T> sub) {
        return locked(() -> {
            if (sub.state.life == SubLife.PREPARING) throw new IllegalStateException("Sub is initializing");
            return subSnapshot(sub.state);
        });
    }
    private SubStats subSnapshot(SubState<T> sub) {
        long pending = slots.values().stream().filter(slot -> slot.owner == sub && slot.acceptedReturn
                && (slot.life == SlotLife.RECYCLING || slot.life == SlotLife.QUARANTINED || slot.life == SlotLife.DESTROYING)).count();
        return new SubStats(sub.id, sub.life.name(), sub.borrows, sub.recycles, sub.forced, sub.outstanding, pending);
    }

    public final PatrolResult patrol() { checkEntry(); return scan(null); }
    PatrolResult patrol(SubContainer<T> sub) { requireActive(sub); return scan(sub.state); }
    private PatrolResult scan(SubState<T> only) {
        locked(() -> { requireOpen(); if (only != null) requireActive(only); return null; });
        if (!scanning.compareAndSet(false, true)) return new PatrolResult(0, 0, 0, 0, 0, 0, true);
        ContainerStats before = stats(); int examined = 0, detected = 0, forced = 0;
        try {
            if (only == null) {
                List<SubState<T>> parents = locked(() -> new ArrayList<>(subs.values()));
                for (SubState<T> sub : parents) {
                    examined++;
                    LeakEvent event = locked(() -> subEvent(sub));
                    if (event != null) { detected++; notifyLeak(event); if (event.wasForceClosed()) forced++; }
                }
            }
            List<HolderLease<T>> leases = locked(() -> slots.values().stream()
                    .filter(slot -> slot.life == SlotLife.BORROWED && (only == null || slot.owner == only))
                    .map(slot -> slot.lease).toList());
            for (HolderLease<T> lease : leases) {
                examined++;
                LeakEvent event = locked(() -> holderEvent(lease));
                if (event != null) { detected++; notifyLeak(event); if (event.wasForceClosed()) forced++; }
            }
            if (only == null) { shrinkIdle(); replenish(); }
            ContainerStats after = stats();
            return new PatrolResult(examined, detected, forced, after.quarantined(),
                    after.destroyedCount() - before.destroyedCount(), after.failureCount() - before.failureCount(), false);
        } finally { scanning.set(false); }
    }
    private LeakEvent subEvent(SubState<T> sub) {
        if (life != Life.OPEN || sub.life != SubLife.ACTIVE) return null;
        long now = now();
        LeakEvent.Reason reason = expired(now, sub.borrowedAt, sub.lastUsedAt, config.subBorrowLimit(), config.subIdleLimit());
        if (reason == null) return null;
        return new LeakEvent(LeakEvent.Target.SUB, sub.id, 0, 1, reason, now - sub.borrowedAt, now - sub.lastUsedAt,
                sub.borrowStack, closeAction(this, LeakEvent.Target.SUB, sub.id, 1));
    }
    private LeakEvent holderEvent(HolderLease<T> lease) {
        if (!active(lease)) return null;
        Slot<T> slot = slots.get(lease.slotId); long now = now();
        LeakEvent.Reason reason = expired(now, slot.borrowedAt, slot.lastUsedAt, config.holderBorrowLimit(), config.holderIdleLimit());
        if (reason == null) return null;
        return new LeakEvent(LeakEvent.Target.HOLDER, slot.id, lease.ownerId, lease.generation, reason,
                now - slot.borrowedAt, now - slot.lastUsedAt, slot.borrowStack,
                closeAction(this, LeakEvent.Target.HOLDER, slot.id, lease.generation));
    }
    /** A retained event owns only a weak Main reference and primitive identity. */
    private static BooleanSupplier closeAction(MainContainer<?> main, LeakEvent.Target target, long id, long generation) {
        var reference = new WeakReference<>(main);
        return () -> {
            MainContainer<?> current = reference.get();
            return current != null && current.forceTarget(target, id, generation);
        };
    }
    private boolean forceTarget(LeakEvent.Target target, long id, long generation) {
        checkEntry();
        if (target == LeakEvent.Target.SUB) {
            SubState<T> sub = locked(() -> subs.get(id));
            return sub != null && forceSub(sub);
        }
        HolderLease<T> lease = locked(() -> {
            Slot<T> slot = slots.get(id);
            return slot != null && slot.generation == generation ? slot.lease : null;
        });
        return lease != null && forceHolder(lease);
    }
    private static LeakEvent.Reason expired(long now, long borrowed, long used, Duration borrowLimit, Duration idleLimit) {
        boolean borrow = borrowLimit != null && now - borrowed >= borrowLimit.toNanos();
        boolean idle = idleLimit != null && now - used >= idleLimit.toNanos();
        if (borrow && idle) return LeakEvent.Reason.BORROW_AND_IDLE_LIMIT;
        if (borrow) return LeakEvent.Reason.BORROW_LIMIT;
        return idle ? LeakEvent.Reason.IDLE_LIMIT : null;
    }
    private void notifyLeak(LeakEvent event) {
        try { listener.onLeak(event); }
        catch (RuntimeException | Error failure) {
            diagnose("Leak listener failed", failure); rethrowFatal(failure);
        }
    }
    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) throw fatal;
        if (failure instanceof ThreadDeath death) throw death;
    }
    private static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }
    private boolean forceHolder(HolderLease<T> lease) {
        checkEntry(); var cleanup = new ArrayList<Slot<T>>();
        boolean accepted = locked(() -> {
            if (!active(lease)) return false;
            Slot<T> slot = slots.get(lease.slotId); markDestroy(slot);
            if (claimCleanup(slot)) cleanup.add(slot); return true;
        });
        runCleanup(cleanup, true); return accepted;
    }
    private boolean forceSub(SubState<T> sub) {
        checkEntry(); var cleanup = new ArrayList<Slot<T>>();
        SubStats snapshot = locked(() -> {
            if (life != Life.OPEN || sub.life != SubLife.ACTIVE) return null;
            var stats = subSnapshot(sub); cleanup.addAll(revoke(sub, true)); return stats;
        });
        if (snapshot == null) return false;
        warnMismatch(snapshot); runCleanup(cleanup, true); return true;
    }
    private void shrinkIdle() {
        List<Slot<T>> cleanup = locked(() -> {
            var actions = new ArrayList<Slot<T>>();
            if (life != Life.OPEN || config.poolIdleLimit() == null) return actions;
            int removable = Math.max(0, slots.size() - config.min()
                    - (int) slots.values().stream().filter(slot -> slot.destroyRequested).count());
            long now = now();
            for (Slot<T> slot : new ArrayList<>(idle)) {
                if (removable == 0) break;
                if (now - slot.idleAt >= config.poolIdleLimit().toNanos()) {
                    markDestroy(slot); if (claimCleanup(slot)) actions.add(slot); removable--;
                }
            }
            return actions;
        });
        runCleanup(cleanup, false);
    }
    private void automaticScan() {
        try { patrol(); }
        catch (RuntimeException | Error failure) {
            if (locked(() -> life != Life.CLOSED)) diagnose("Automatic patrol failed", failure);
            rethrowFatal(failure);
        }
    }

    @Override public final void close() {
        checkEntry(); var warnings = new ArrayList<SubStats>();
        List<Slot<T>> cleanup = locked(() -> {
            var actions = new ArrayList<Slot<T>>();
            if (life == Life.CLOSED) return actions;
            life = Life.CLOSED;
            if (scheduler != null) { scheduler.shutdown(); scheduler = null; }
            for (SubState<T> sub : new ArrayList<>(subs.values())) {
                if (sub.life == SubLife.ACTIVE) warnings.add(subSnapshot(sub));
                actions.addAll(revoke(sub, true));
            }
            for (Slot<T> slot : slots.values()) {
                markDestroy(slot); if (claimCleanup(slot)) actions.add(slot);
            }
            return actions;
        });
        warnings.forEach(this::warnMismatch); runCleanup(cleanup, false);
    }
}
