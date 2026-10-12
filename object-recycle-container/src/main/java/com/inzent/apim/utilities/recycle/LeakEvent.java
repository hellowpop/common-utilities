package com.inzent.apim.utilities.recycle;

import java.time.Instant;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Detection snapshot. The bound close action is safe to retain and call from another thread.
 * It weakly references Main and resolves the original identity/generation at invocation time;
 * retaining this event does not keep the pool alive. forceClose returns false once the
 * target is returned/revoked or Main is no longer reachable.
 */
public final class LeakEvent {
    public enum Target { SUB, HOLDER }
    public enum Reason { BORROW_LIMIT, IDLE_LIMIT, BORROW_AND_IDLE_LIMIT }
    private final Target target;
    private final long targetId, parentId, generation, borrowedNanos, idleNanos;
    private final Reason reason;
    private final Instant detectedAt;
    private final List<StackTraceElement> borrowStack;
    private final BooleanSupplier closeAction;
    private final AtomicBoolean forceClosed = new AtomicBoolean();

    LeakEvent(Target target, long targetId, long parentId, long generation, Reason reason,
            long borrowedNanos, long idleNanos, List<StackTraceElement> borrowStack, BooleanSupplier closeAction) {
        this.target = target; this.targetId = targetId; this.parentId = parentId;
        this.generation = generation; this.reason = reason; this.borrowedNanos = borrowedNanos;
        this.idleNanos = idleNanos; this.detectedAt = Instant.now();
        this.borrowStack = List.copyOf(borrowStack); this.closeAction = closeAction;
    }
    public Target target() { return target; }
    public long targetId() { return targetId; }
    public long parentId() { return parentId; }
    public long generation() { return generation; }
    public Reason reason() { return reason; }
    public Instant detectedAt() { return detectedAt; }
    public long borrowedNanos() { return borrowedNanos; }
    public long idleNanos() { return idleNanos; }
    public List<StackTraceElement> borrowStack() { return borrowStack; }
    public boolean forceClose() {
        boolean closed = closeAction.getAsBoolean();
        if (closed) forceClosed.set(true);
        return closed;
    }
    boolean wasForceClosed() { return forceClosed.get(); }
}
