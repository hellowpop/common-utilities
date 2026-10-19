package com.inzent.apim.utilities.recycle;

import java.util.List;
import java.util.concurrent.*;

/** Java 17 executor scope for tests that need workers drained before disposing their pool. */
final class ExecutorResource extends AbstractExecutorService implements AutoCloseable {
    private final ExecutorService delegate;
    private ExecutorResource(ExecutorService delegate) { this.delegate = delegate; }
    static ExecutorResource singleThread() { return new ExecutorResource(Executors.newSingleThreadExecutor()); }
    static ExecutorResource fixedThreads(int size) { return new ExecutorResource(Executors.newFixedThreadPool(size)); }
    @Override public void execute(Runnable command) { delegate.execute(command); }
    @Override public void shutdown() { delegate.shutdown(); }
    @Override public List<Runnable> shutdownNow() { return delegate.shutdownNow(); }
    @Override public boolean isShutdown() { return delegate.isShutdown(); }
    @Override public boolean isTerminated() { return delegate.isTerminated(); }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException { return delegate.awaitTermination(timeout,unit); }
    @Override public void close() {
        shutdown();
        boolean interrupted = false;
        try {
            while (!isTerminated()) {
                try {
                    if (!awaitTermination(10,TimeUnit.SECONDS)) {
                        shutdownNow();
                        if (!awaitTermination(5,TimeUnit.SECONDS)) throw new IllegalStateException("Test workers did not terminate");
                    }
                } catch (InterruptedException e) { interrupted = true; shutdownNow(); }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
