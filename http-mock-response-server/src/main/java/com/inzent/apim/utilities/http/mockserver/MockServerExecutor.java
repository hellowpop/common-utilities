package com.inzent.apim.utilities.http.mockserver;

import java.io.Closeable;
import java.io.IOException;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Embedded mock server. Owns all sockets and threads; callers must stop or close it. */
public final class MockServerExecutor implements Closeable {
    private enum State { NEW, STARTING, RUNNING, STOPPING, STOPPED, FAILED }
    private final ServerOptions options;
    private final Object lifecycle = new Object();
    private final Object registryLock = new Object();
    private final List<MockBinding> bindings = new ArrayList<>();
    private final ConcurrentMap<Long, ClientSession> sessions = new ConcurrentHashMap<>();
    private final Semaphore slots;
    private final AtomicLong clientIds = new AtomicLong();
    private final CountDownLatch startGate = new CountDownLatch(1);
    private final CountDownLatch startupFinished = new CountDownLatch(1);
    private volatile State state = State.NEW;
    private boolean operationInProgress;
    private ThreadPoolExecutor workers;
    private ExecutorService closers;
    private ScheduledThreadPoolExecutor scheduler;
    private volatile Thread shutdownThread;
    private volatile Throwable shutdownFailure;
    private volatile boolean failedStart;
    private static final AtomicLong THREAD_IDS = new AtomicLong();

    public MockServerExecutor() { this(ServerOptions.defaults()); }
    public MockServerExecutor(ServerOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        slots = new Semaphore(options.maxClients());
    }

    public <E extends Enum<E> & MockScenario> void addMock(E mock, InetAddress address, int port,
                                                         ServiceStatusListener listener) {
        addMock(mock, address, port, MockOptions.defaults(), listener);
    }
    public <E extends Enum<E> & MockScenario> void addMock(E mock, InetAddress address, int port,
                                                         MockOptions options, ServiceStatusListener listener) {
        Objects.requireNonNull(mock, "mock");
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(listener, "listener");
        if (port < 0 || port > 65535) throw new IllegalArgumentException("port must be 0..65535");
        enterOperation();
        try {
            synchronized (registryLock) {
                if (state != State.NEW) throw new IllegalStateException("Registration requires NEW state");
                var endpoint = new InetSocketAddress(address, port);
                if (port != 0 && bindings.stream().anyMatch(b -> b.address.equals(endpoint)))
                    throw new IllegalArgumentException("Duplicate bind address: " + endpoint);
                bindings.add(new MockBinding(bindings.size() + 1L, mock, mock.name(), endpoint, options, listener));
            }
        } finally { leaveOperation(); }
    }

    public void start() {
        enterOperation();
        boolean starting = false;
        try {
            synchronized (registryLock) {
                if (state == State.RUNNING) return;
                if (state != State.NEW) throw new IllegalStateException("Cannot start from " + state);
                if (bindings.isEmpty()) throw new IllegalStateException("No mocks registered");
                state = State.STARTING;
                starting = true;
            }
            try {
                for (MockBinding binding : bindings) {
                    ensureStarting();
                    binding.started = true;
                    binding.event(ServiceEventType.SERVICE_START, null);
                    ensureStarting();
                    try { binding.open(); }
                    catch (Exception failure) {
                        binding.event(ServiceEventType.EXCEPTION_RECEIVED, failure);
                        throw failure;
                    }
                }
                ensureStarting();
                workers = new ThreadPoolExecutor(0, options.maxClients(), 30, TimeUnit.SECONDS,
                    new SynchronousQueue<>(), threadFactory("client"));
                closers = Executors.newCachedThreadPool(threadFactory("close"));
                scheduler = new ScheduledThreadPoolExecutor(1, threadFactory("deadline"));
                scheduler.setRemoveOnCancelPolicy(true);
                for (MockBinding binding : bindings) {
                    binding.acceptThread = threadFactory("accept").newThread(() -> acceptLoop(binding));
                    binding.acceptThread.start();
                }
                synchronized (registryLock) {
                    ensureStarting();
                    state = State.RUNNING;
                }
                startGate.countDown();
            } catch (Exception | Error failure) {
                failedStart = true;
                // The coordinator must not wait for this thread while this thread awaits rollback.
                startupFinished.countDown();
                beginShutdown();
                try { awaitShutdown(); }
                catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                if (failure instanceof Error fatal) throw fatal;
                throw new MockServerStartException("Unable to start all mock bindings", failure);
            }
        } finally {
            if (starting) startupFinished.countDown();
            leaveOperation();
        }
    }

    private void ensureStarting() {
        if (state != State.STARTING) throw new IllegalStateException("Startup cancelled by stop");
    }

    private void acceptLoop(MockBinding binding) {
        ExecutionGuard.enter();
        try {
            startGate.await();
            while (state == State.RUNNING && !binding.failed) {
                Socket socket = binding.socket.accept();
                ClientSession session;
                boolean admitted = false;
                try {
                    synchronized (registryLock) {
                        admitted = state == State.RUNNING && !binding.failed && slots.tryAcquire();
                        session = new ClientSession(this, binding, socket, clientIds.incrementAndGet(), admitted);
                        sessions.put(session.id, session);
                        if (state != State.RUNNING) session.cancellation.set(TerminationReason.STOP);
                    }
                } catch (RuntimeException | Error failure) {
                    if (admitted) slots.release();
                    try { socket.close(); }
                    catch (Throwable close) { if (close != failure) failure.addSuppressed(close); }
                    throw failure;
                }
                if (!session.admitted) { session.run(); continue; }
                try { workers.execute(session); }
                catch (RejectedExecutionException rejected) {
                    session.abortBeforeRun(null, state == State.RUNNING
                        ? TerminationReason.CAPACITY_REJECTED : TerminationReason.STOP);
                } catch (RuntimeException | Error failure) {
                    try { session.abortBeforeRun(failure, TerminationReason.ERROR); }
                    catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                    throw failure;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            if (state == State.RUNNING) failBinding(binding, failure);
        } catch (Error fatal) {
            failBinding(binding, fatal);
            throw fatal;
        } finally {
            ExecutionGuard.exit();
        }
    }

    private void failBinding(MockBinding binding, Throwable failure) {
        binding.failed = true;
        try { binding.closeSocket(); }
        catch (IOException close) { failure.addSuppressed(close); }
        for (ClientSession session : sessions.values())
            if (session.binding == binding) session.cancel(TerminationReason.ERROR);
        try { binding.event(ServiceEventType.EXCEPTION_RECEIVED, failure); }
        finally { binding.closedEvent(); }
    }

    public void stop() {
        ExecutionGuard.requireCaller();
        synchronized (registryLock) {
            if (state == State.NEW) { state = State.STOPPED; return; }
            if (state == State.STOPPED && shutdownFailure == null) return;
            if (state == State.FAILED && shutdownThread == null) return;
            beginShutdown();
        }
        awaitShutdown();
    }
    @Override public void close() { stop(); }

    private void beginShutdown() {
        synchronized (registryLock) {
            if (shutdownThread != null && (shutdownThread.isAlive() || state != State.STOPPING)) return;
            shutdownFailure = null;
            state = State.STOPPING;
            startGate.countDown();
            shutdownThread = threadFactory("shutdown").newThread(this::shutdownResources);
            shutdownThread.start();
        }
    }

    private void shutdownResources() {
        ExecutionGuard.enter();
        try {
            // No callbacks run until cancellation and socket closure have been requested everywhere.
            for (MockBinding binding : bindings) {
                try { binding.closeSocket(); }
                catch (Throwable failure) { recordShutdownFailure(failure); }
            }
            synchronized (registryLock) {
                for (ClientSession session : sessions.values()) cleanupStep(() -> session.cancel(TerminationReason.STOP));
            }
            // Startup callbacks may block. The caller's deadline still applies while this
            // coordinator waits, and any resources published by startup are closed again below.
            startupFinished.await();
            for (MockBinding binding : bindings) {
                try { binding.closeSocket(); }
                catch (Throwable failure) { recordShutdownFailure(failure); }
            }
            synchronized (registryLock) {
                for (ClientSession session : sessions.values()) cleanupStep(() -> session.cancel(TerminationReason.STOP));
            }
            cleanupStep(() -> { if (workers != null) workers.shutdown(); });
            cleanupStep(() -> { if (scheduler != null) scheduler.shutdownNow(); });
            for (MockBinding binding : bindings) {
                Thread thread = binding.acceptThread;
                if (thread != null && (binding.socket == null || binding.socket.isClosed())) thread.join();
            }
            // Late accepts have completed before the close executor is shut down.
            awaitTermination(scheduler);
            cleanupStep(() -> { if (closers != null) closers.shutdown(); });
            awaitTermination(closers);
            awaitTermination(workers);
            if (!sessions.isEmpty() || !terminated(workers) || !terminated(scheduler) || !terminated(closers)
                || bindings.stream().anyMatch(b -> (b.socket != null && !b.socket.isClosed())
                    || (b.acceptThread != null && b.acceptThread.isAlive()))) return;
            for (MockBinding binding : bindings) {
                try { binding.closedEvent(); }
                catch (Throwable failure) { recordShutdownFailure(failure); }
            }
            for (MockBinding binding : bindings) {
                if (binding.started) {
                    try { binding.event(ServiceEventType.SERVICE_STOP, null); }
                    catch (Throwable failure) { recordShutdownFailure(failure); }
                }
            }
            state = failedStart ? State.FAILED : State.STOPPED;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            recordShutdownFailure(interrupted);
        } finally {
            ExecutionGuard.exit();
        }
    }

    private static void awaitTermination(ExecutorService executor) throws InterruptedException {
        if (executor != null && executor.isShutdown()) while (!executor.awaitTermination(1, TimeUnit.DAYS)) { /* wait in coordinator */ }
    }
    private interface CleanupStep { void run() throws Exception; }
    private void cleanupStep(CleanupStep step) {
        try { step.run(); }
        catch (Throwable failure) { recordShutdownFailure(failure); }
    }
    private static boolean terminated(ExecutorService executor) {
        return executor == null || executor.isTerminated();
    }
    private void recordShutdownFailure(Throwable failure) {
        if (shutdownFailure == null) shutdownFailure = failure;
        else if (shutdownFailure != failure) shutdownFailure.addSuppressed(failure);
    }
    private void awaitShutdown() {
        Thread thread = shutdownThread;
        if (thread == null) return;
        long nanos = options.shutdownTimeout().toNanos();
        try {
            TimeUnit.NANOSECONDS.timedJoin(thread, nanos);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new MockServerStopException("Interrupted while awaiting shutdown; cleanup continues", interrupted);
        }
        if (thread.isAlive()) throw new MockServerStopException("Shutdown timeout; cleanup continues, call stop again");
        if (shutdownFailure != null) throw new MockServerStopException("Shutdown failed", shutdownFailure);
        if (state == State.STOPPING) throw new MockServerStopException("Shutdown incomplete");
    }

    void sessionFinished(ClientSession session) {
        if (sessions.remove(session.id, session) && session.admitted) slots.release();
    }
    void closeAsync(ClientSession session) {
        ExecutorService executor = closers;
        if (executor == null || executor.isShutdown()) { session.forceClose(); return; }
        try { executor.execute(session::forceClose); }
        catch (RuntimeException failure) {
            session.forceClose();
            logCleanupFailure("Close task submission failed; socket closed directly", failure);
        } catch (Error fatal) {
            session.forceClose();
            throw fatal;
        }
    }

    private static void logCleanupFailure(String message, Throwable failure) {
        try { System.getLogger(MockServerExecutor.class.getName()).log(System.Logger.Level.WARNING, message, failure); }
        catch (Exception loggingFailure) { /* diagnostics must not interrupt resource cleanup */ }
    }
    ScheduledFuture<?> schedule(Runnable task, Duration duration) {
        return scheduler.schedule(task, duration.toNanos(), TimeUnit.NANOSECONDS);
    }

    private void enterOperation() {
        ExecutionGuard.requireCaller();
        synchronized (lifecycle) {
            while (operationInProgress) {
                try { lifecycle.wait(); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while awaiting lifecycle operation", interrupted);
                }
            }
            operationInProgress = true;
        }
    }
    private void leaveOperation() {
        synchronized (lifecycle) {
            operationInProgress = false;
            lifecycle.notifyAll();
        }
    }
    private static ThreadFactory threadFactory(String role) {
        return task -> {
            Thread thread = new Thread(task, "http-mock-" + role + "-" + THREAD_IDS.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
    }
}
