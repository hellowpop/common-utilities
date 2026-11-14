package com.inzent.apim.utilities.http.caller.execution;

import java.util.concurrent.*;

/** Disarming and interrupt delivery share a lock, so pooled threads never receive stale interrupts. */
final class DeadlineGuard implements AutoCloseable {
    private final Thread owner=Thread.currentThread();
    private final ScheduledFuture<?> future;
    private boolean armed=true;
    DeadlineGuard(ScheduledExecutorService timer,long delayNanos){future=timer.schedule(this::expire,Math.max(0,delayNanos),TimeUnit.NANOSECONDS);}
    synchronized void expire(){if(armed)owner.interrupt();}
    @Override public synchronized void close(){armed=false;future.cancel(false);}
}
