package com.inzent.apim.utilities.http.mockserver;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/**
 * Standalone diagnostic probe for commit a6bf160.
 * Faults are injected only into this probe's server instances via reflection.
 * Checks verify corrected ownership, shutdown and event diagnostics.
 */
public final class SafetyProbe {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    private static int checks;

    enum Reply implements MockScenario {
        SMALL, LARGE;
        public void execute(MockClientContext context) throws Exception {
            context.runPoint("respond", () -> {
                byte[] bytes = new byte[this == LARGE ? 1024 * 1024 : 3];
                throw new SendResponseException(new MockResponse(bytes));
            });
        }
    }

    private static final class Events implements ServiceStatusListener {
        final AtomicInteger port = new AtomicInteger();
        final AtomicInteger disconnected = new AtomicInteger();
        final CountDownLatch bindingFailed = new CountDownLatch(1);
        final CountDownLatch reading = new CountDownLatch(1);
        public void onEvent(ServiceEvent e) {
            if (e.type() == ServiceEventType.SERVER_SOCKET_OPEN) port.set(e.localAddress().getPort());
            if (e.type() == ServiceEventType.CLIENT_DISCONNECTED) disconnected.incrementAndGet();
            if (e.type() == ServiceEventType.SERVER_SOCKET_CLOSED) bindingFailed.countDown();
            if (e.type() == ServiceEventType.POINT_ENTER && "readChunk".equals(e.point())) reading.countDown();
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Runtime=" + System.getProperty("java.version"));
        normalResponseResourcesAreReclaimed();
        workerSubmissionFailureRetainsSessionAndPermit();
        closerSubmissionFailureAbandonsShutdown();
        retainedEventsKeepResponseBodies();
        nullBoundaryAndListenerNpeIsolation();
        System.out.println("Completed diagnostic checks=" + checks);
    }

    private static void normalResponseResourcesAreReclaimed() throws Exception {
        Events events = new Events();
        MockServerExecutor server = new MockServerExecutor();
        server.addMock(Reply.SMALL, LOOPBACK, 0, events);
        server.start();
        try {
            for (int i = 0; i < 100; i++) {
                try (Socket client = connect(events.port.get())) {
                    client.getInputStream().transferTo(OutputStream.nullOutputStream());
                }
            }
            await(() -> events.disconnected.get() == 100 && sessions(server).isEmpty()
                && slots(server).availablePermits() == 64, "normal sessions finish");
            ScheduledThreadPoolExecutor scheduler = field(server, "scheduler");
            await(() -> scheduler.getQueue().isEmpty(), "cancelled deadlines removed");
            check(slots(server).availablePermits() == 64, "NORMAL: 100 responses return all 64 permits");
            check(sessions(server).isEmpty() && scheduler.getQueue().isEmpty(),
                "NORMAL: sessions=0, deadline queue=0");
        } finally { server.stop(); }
        ThreadPoolExecutor workers = field(server, "workers");
        ExecutorService closers = field(server, "closers");
        ScheduledThreadPoolExecutor scheduler = field(server, "scheduler");
        check(workers.isTerminated() && closers.isTerminated() && scheduler.isTerminated(),
            "NORMAL: all three executors terminated after stop");
    }

    private static void workerSubmissionFailureRetainsSessionAndPermit() throws Exception {
        Events events = new Events();
        MockServerExecutor server = new MockServerExecutor();
        server.addMock(MockCase.WAIT_FOR_CLIENT_DISCONNECT, LOOPBACK, 0, events);
        server.start();
        ThreadPoolExecutor workers = field(server, "workers");
        ThreadFactory original = workers.getThreadFactory();
        workers.setThreadFactory(task -> { throw new SecurityException("probe: worker creation denied"); });
        try (Socket client = connect(events.port.get())) {
            check(events.bindingFailed.await(3, TimeUnit.SECONDS), "FAULT: failed worker closes binding");
            check(client.getInputStream().read() == -1, "FAULT: accepted socket is closed after worker failure");
            check(sessions(server).isEmpty() && slots(server).availablePermits() == 64,
                "FIXED: worker submission failure returns sessions=0, permits=64");
        } finally {
            workers.setThreadFactory(original);
            server.stop();
        }
        check(sessions(server).isEmpty() && slots(server).availablePermits() == 64,
            "FIXED: stop retains no unstarted session or consumed permit");
    }

    private static void closerSubmissionFailureAbandonsShutdown() throws Exception {
        Events events = new Events();
        MockServerExecutor server = new MockServerExecutor(new ServerOptions(2, Duration.ofSeconds(1)));
        server.addMock(MockCase.WAIT_FOR_CLIENT_DISCONNECT, LOOPBACK, 0, events);
        server.start();
        ThreadPoolExecutor closers = field(server, "closers");
        ThreadFactory original = closers.getThreadFactory();
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        Thread.UncaughtExceptionHandler prior = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            if (thread.getName().startsWith("http-mock-shutdown-")) uncaught.set(failure);
            else if (prior != null) prior.uncaughtException(thread, failure);
        });
        try (Socket client = connect(events.port.get())) {
            check(events.reading.await(3, TimeUnit.SECONDS), "FAULT: client reaches read point");
            await(() -> sessions(server).values().stream().anyMatch(session ->
                session.runner != null && Arrays.stream(session.runner.getStackTrace()).anyMatch(frame ->
                    frame.getClassName().contains("Socket") && frame.getMethodName().startsWith("read"))),
                "worker blocks inside socket read");
            closers.setThreadFactory(task -> { throw new SecurityException("probe: closer creation denied"); });
            server.stop();
            ThreadPoolExecutor workers = field(server, "workers");
            check(workers.isTerminated() && closers.isTerminated(),
                "FIXED: closer creation failure falls back and pools terminate");
            server.stop();
            check(sessions(server).isEmpty(), "FIXED: repeated stop has no pending sessions");
            check(client.getInputStream().read() == -1, "FIXED: accepted socket closed");        } finally {
            closers.setThreadFactory(original);
            // Defensive cleanup for a failing probe assertion.
            emergencyCleanup(server);
            Thread.setDefaultUncaughtExceptionHandler(prior);
        }
    }

    private static void retainedEventsKeepResponseBodies() throws Exception {
        List<ServiceEvent> retained = new CopyOnWriteArrayList<>();
        Events events = new Events();
        MockServerExecutor server = new MockServerExecutor();
        server.addMock(Reply.LARGE, LOOPBACK, 0, ServiceStatusListenerChain.of(events, retained::add));
        server.start();
        try {
            for (int i = 0; i < 4; i++) {
                try (Socket client = connect(events.port.get())) {
                    client.getInputStream().transferTo(OutputStream.nullOutputStream());
                }
            }
        } finally { server.stop(); }
        long bytes = retained.stream().filter(e -> e.exception() instanceof SendResponseException)
            .mapToLong(e -> ((SendResponseException) e.exception()).response().contentLength()).sum();
        check(sessions(server).isEmpty() && bytes == 0 && retained.stream().anyMatch(e -> e.exception() instanceof ThrowableSnapshot),
            "FIXED: saved events contain snapshots and retain no response action body");
        retained.clear();
    }

    private static void nullBoundaryAndListenerNpeIsolation() throws Exception {
        ServiceStatusListener empty = ServiceStatusListenerChain.of();
        try (MockServerExecutor server = new MockServerExecutor()) {
            expectNpe(() -> new MockServerExecutor(null));
            expectNpe(() -> server.addMock((MockCase) null, LOOPBACK, 0, empty));
            expectNpe(() -> server.addMock(MockCase.SLOW_READ, null, 0, empty));
            expectNpe(() -> server.addMock(MockCase.SLOW_READ, LOOPBACK, 0, (ServiceStatusListener) null));
            expectNpe(() -> server.addMock(MockCase.SLOW_READ, LOOPBACK, 0, null, empty));
            expectNpe(() -> MockOptions.builder().readInterval(null).build());
            expectNpe(() -> MockOptions.builder().responseTimeout(null).build());
            expectNpe(() -> new ServerOptions(1, null));
            expectNpe(() -> new CloseClientException(null));
            expectNpe(() -> new SendResponseException(null));
            expectNpe(() -> new MockResponse(200, null, new byte[0]));
            expectNpe(() -> new MockResponse(200, "text/plain", null));
            expectNpe(() -> MockResponse.utf8(200, "text/plain", null));
            expectNpe(() -> ServiceStatusListenerChain.of((ServiceStatusListener[]) null));
            expectNpe(() -> ServiceStatusListenerChain.of((ServiceStatusListener) null));
            expectNpe(() -> ServiceStatusListenerChain.of().andThen(null));
            expectNpe(() -> ServiceStatusListenerChain.of().onEvent(null));
            check(sessions(server).isEmpty() && slots(server).availablePermits() == 64,
                "NULL: 17 required-null boundaries rejected before resource allocation");
        }
        ServiceEvent serviceEvent = new ServiceEvent(Instant.now(), 1, "probe", null,
            ServiceEventType.SERVICE_START, null, null, null, 0, 0, null, null, false, null, Map.of());
        AtomicInteger next = new AtomicInteger(), logged = new AtomicInteger();
        ServiceStatusListenerChain chain = new ServiceStatusListenerChain(
            List.of(e -> e.response().statusCode(), e -> next.incrementAndGet()),
            (index, event, failure) -> {
                if (failure instanceof NullPointerException) logged.incrementAndGet();
                throw new NullPointerException("probe: logger");
            });
        chain.onEvent(serviceEvent);
        check(logged.get() == 1 && next.get() == 1,
            "NULL: nullable event dereference and logger NPE isolated; next listener runs");
    }

    private static Socket connect(int port) throws Exception {
        Socket socket = new Socket(LOOPBACK, port);
        socket.setSoTimeout(3000);
        return socket;
    }
    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(target);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static ConcurrentMap<Long, ClientSession> sessions(MockServerExecutor server) {
        return field(server, "sessions");
    }
    private static Semaphore slots(MockServerExecutor server) { return field(server, "slots"); }
    private static void await(BooleanSupplier condition, String message) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= end) throw new AssertionError("Timeout: " + message);
            Thread.sleep(10);
        }
    }
    private static void expectNpe(Runnable action) {
        try { action.run(); throw new AssertionError("Expected required-null rejection"); }
        catch (NullPointerException expected) { }
    }
    private static void expectStopFailure(MockServerExecutor server) {
        try { server.stop(); throw new AssertionError("Expected incomplete shutdown"); }
        catch (MockServerStopException expected) { }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
        System.out.println(message);
    }
    private static void emergencyCleanup(MockServerExecutor server) throws Exception {
        for (ClientSession session : sessions(server).values()) {
            session.forceClose();
            Thread runner = session.runner;
            if (runner != null) runner.interrupt();
        }
        List<MockBinding> bindings = field(server, "bindings");
        for (MockBinding binding : bindings) binding.closeSocket();
        for (String name : List.of("workers", "scheduler", "closers")) {
            ExecutorService executor = field(server, name);
            executor.shutdownNow();
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) throw new AssertionError("Cleanup failed: " + name);
        }
        for (MockBinding binding : bindings) if (binding.acceptThread != null) binding.acceptThread.join(3000);
    }
}
