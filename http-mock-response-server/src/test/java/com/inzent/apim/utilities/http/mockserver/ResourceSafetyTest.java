package com.inzent.apim.utilities.http.mockserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class ResourceSafetyTest {
    enum Point { REPLY }
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    enum Replies implements MockScenario {
        LARGE, WRAPPED;
        public void execute(MockClientContext clientContext) throws Exception {
            clientContext.runPoint(Point.REPLY, () -> {
                var action = new SendResponseException(new MockResponse(new byte[1024 * 1024]));
                if (this == WRAPPED) {
                    IOException wrapped = new IOException("wrapped", action);
                    wrapped.addSuppressed(action);
                    throw wrapped;
                }
                throw action;
            });
        }
    }

    @Test void workerCreationFailureReclaimsUnstartedSessionAndPermit() throws Exception {
        var events = new ServerIntegrationTest.Events();
        var server = new MockServerExecutor();
        server.addMock(MockCase.WAIT_FOR_CLIENT_DISCONNECT, LOOPBACK, 0, events);
        server.start();
        ThreadPoolExecutor workers = field(server, "workers");
        var original = workers.getThreadFactory();
        workers.setThreadFactory(task -> { throw new SecurityException("worker creation"); });
        try (Socket socket = ServerIntegrationTest.connect(events.port())) {
            events.await(ServiceEventType.SERVER_SOCKET_CLOSED);
            assertEquals(-1, socket.getInputStream().read());
            assertTrue(sessions(server).isEmpty(), "unstarted session must be removed");
            assertEquals(64, ((Semaphore) field(server, "slots")).availablePermits());
            assertEquals(1, events.count(ServiceEventType.CLIENT_DISCONNECTED));
        } finally { workers.setThreadFactory(original); server.stop(); }
        assertTrue(sessions(server).isEmpty());
    }

    @Test void closerCreationFailureFallsBackAndDoesNotAbandonShutdown() throws Exception {
        var events = new ServerIntegrationTest.Events();
        var server = new MockServerExecutor();
        server.addMock(MockCase.WAIT_FOR_CLIENT_DISCONNECT, LOOPBACK, 0, events);
        server.start();
        ThreadPoolExecutor closers = field(server, "closers");
        var original = closers.getThreadFactory();
        try (Socket socket = ServerIntegrationTest.connect(events.port())) {
            awaitBlockedRead(server);
            closers.setThreadFactory(task -> { throw new SecurityException("closer creation"); });
            assertDoesNotThrow(server::stop);
            assertEquals(-1, socket.getInputStream().read());
            assertTrue(sessions(server).isEmpty());
            assertAllTerminated(server);
            assertEquals(1, events.count(ServiceEventType.SERVICE_STOP));
        } finally { closers.setThreadFactory(original); emergencyCleanup(server); }
    }

    @Test void shutdownStepFailureStillClosesOthersAndCanBeRetried() throws Exception {
        var events = new ServerIntegrationTest.Events();
        var server = new MockServerExecutor(new ServerOptions(2, Duration.ofSeconds(1)));
        server.addMock(MockCase.WAIT_FOR_CLIENT_DISCONNECT, LOOPBACK, 0, events);
        server.start();
        ThreadPoolExecutor oldWorkers = field(server, "workers");
        oldWorkers.shutdown();
        var replacement = new FailingShutdownPool();
        setField(server, "workers", replacement);
        try (Socket socket = ServerIntegrationTest.connect(events.port())) {
            awaitBlockedRead(server);
            assertThrows(MockServerStopException.class, server::stop);
            assertEquals(-1, socket.getInputStream().read());
            assertTrue(((ExecutorService) field(server, "closers")).isTerminated());
            assertTrue(((ExecutorService) field(server, "scheduler")).isTerminated());
            assertEquals(0, events.count(ServiceEventType.SERVICE_STOP));
            replacement.fail.set(false);
            assertDoesNotThrow(server::stop);
            assertAllTerminated(server);
            assertEquals(1, events.count(ServiceEventType.SERVICE_STOP));
        } finally { replacement.fail.set(false); emergencyCleanup(server); }
    }

    @Test void eventDiagnosticsDoNotKeepRawActionOrResponseBody() throws Exception {
        for (Replies reply : Replies.values()) {
            var events = new ServerIntegrationTest.Events();
            try (var server = new MockServerExecutor()) {
                server.addMock(reply, LOOPBACK, 0, events);
                server.start();
                try (Socket socket = ServerIntegrationTest.connect(events.port())) {
                    socket.getInputStream().transferTo(OutputStream.nullOutputStream());
                }
                server.stop();
                List<Throwable> exceptions = events.all.stream().map(ServiceEvent::exception)
                    .filter(Objects::nonNull).toList();
                assertFalse(exceptions.isEmpty());
                for (Throwable failure : exceptions) assertNoRawAction(failure, new HashSet<>());
                if (reply == Replies.LARGE) {
                    ServiceEvent action = events.all.stream().filter(ServiceEvent::action).findFirst().orElseThrow();
                    assertNotNull(action.response(), "action metadata must retain body length, not bytes");
                    assertEquals(1024 * 1024, action.response().contentLength());
                }
            }
        }
    }

    private static void assertNoRawAction(Throwable failure, Set<Throwable> visited) {
        if (failure == null || !visited.add(failure)) return;
        assertInstanceOf(ThrowableSnapshot.class, failure);
        assertFalse(failure instanceof NextActionException, "event must not retain executable action");
        assertNoRawAction(failure.getCause(), visited);
        for (Throwable suppressed : failure.getSuppressed()) assertNoRawAction(suppressed, visited);
    }

    @Test void snapshotsBoundCyclesAndPreserveCloseMetadata() {
        var close = new CloseClientException(Duration.ofMillis(25));
        var wrapped = new IOException("x".repeat(5000), close);
        close.initCause(wrapped);
        var copy = ThrowableSnapshot.capture(wrapped);
        assertEquals(4096, copy.getMessage().length());
        assertEquals(IOException.class.getName(), copy.originalType());
        var action = assertInstanceOf(ThrowableSnapshot.class, copy.getCause());
        assertEquals(Duration.ofMillis(25), action.closeDelay());
        assertNull(action.getCause());
        assertArrayEquals(Arrays.copyOf(close.getStackTrace(), Math.min(64, close.getStackTrace().length)),
            action.getStackTrace());
    }

    private static final class FailingShutdownPool extends ThreadPoolExecutor {
        final AtomicBoolean fail = new AtomicBoolean(true);
        FailingShutdownPool() { super(0, 2, 30, TimeUnit.SECONDS, new SynchronousQueue<>()); }
        @Override public void shutdown() {
            if (fail.get()) throw new SecurityException("shutdown denied");
            super.shutdown();
        }
    }
    static void awaitBlockedRead(MockServerExecutor server) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < end) {
            for (ClientSession session : sessions(server).values()) {
                Thread runner = session.runner;
                if (runner != null && Arrays.stream(runner.getStackTrace()).anyMatch(frame ->
                    frame.getClassName().contains("Socket") && frame.getMethodName().startsWith("read"))) return;
            }
            Thread.sleep(10);
        }
        fail("Client did not enter socket read");
    }
    @SuppressWarnings("unchecked") static <T> T field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(object);
    }
    static void setField(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }
    static Map<Long, ClientSession> sessions(MockServerExecutor server) throws Exception { return field(server, "sessions"); }
    static void assertAllTerminated(MockServerExecutor server) throws Exception {
        for (String name : List.of("workers", "closers", "scheduler"))
            assertTrue(((ExecutorService) field(server, name)).isTerminated(), name);
    }
    static void emergencyCleanup(MockServerExecutor server) throws Exception {
        for (ClientSession session : List.copyOf(sessions(server).values())) {
            session.forceClose();
            Thread thread = session.runner;
            if (thread != null) thread.interrupt();
        }
        List<MockBinding> bindings = field(server, "bindings");
        for (MockBinding binding : bindings) binding.closeSocket();
        for (String name : List.of("workers", "scheduler", "closers")) {
            ExecutorService executor = field(server, name);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
        for (MockBinding binding : bindings) if (binding.acceptThread != null) binding.acceptThread.join(3000);
    }
}
