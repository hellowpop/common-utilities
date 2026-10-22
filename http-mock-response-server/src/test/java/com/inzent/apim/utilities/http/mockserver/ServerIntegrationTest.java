package com.inzent.apim.utilities.http.mockserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class ServerIntegrationTest {
    static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    enum Cases implements MockScenario {
        RESPONSE {
            public void execute(MockClientContext c) throws Exception {
                c.runPoint("reply", () -> { throw new SendResponseException(MockResponse.utf8(200, "text/plain; charset=UTF-8", "가")); });
                throw new AssertionError("action must not return");
            }
        },
        DELAY_CLOSE {
            public void execute(MockClientContext c) throws Exception {
                c.runPoint("close", () -> { throw new CloseClientException(Duration.ofMillis(250)); });
            }
        },
        LONG_CLOSE {
            public void execute(MockClientContext c) throws Exception {
                c.runPoint("close", () -> { throw new CloseClientException(Duration.ofSeconds(30)); });
            }
        },
        FAILURE {
            public void execute(MockClientContext c) throws Exception {
                c.runPoint("broken", () -> { throw new IOException("injected point failure"); });
                throw new AssertionError("must not run");
            }
        },
        HUGE_RESPONSE {
            public void execute(MockClientContext c) throws Exception {
                c.runPoint("reply", () -> { throw new SendResponseException(new MockResponse(new byte[16 * 1024 * 1024])); });
            }
        },
        UNKNOWN_ACTION {
            public void execute(MockClientContext c) throws Exception {
                c.runPoint("unknown", () -> { throw new NextActionException("unknown") {}; });
            }
        }
    }

    static class Events implements ServiceStatusListener {
        final BlockingQueue<ServiceEvent> queue = new LinkedBlockingQueue<>();
        final List<ServiceEvent> all = new CopyOnWriteArrayList<>();
        public void onEvent(ServiceEvent e) { all.add(e); queue.add(e); }
        ServiceEvent await(ServiceEventType type) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (System.nanoTime() < deadline) {
                var e = queue.poll(100, TimeUnit.MILLISECONDS);
                if (e != null && e.type() == type) return e;
            }
            fail("Missing event " + type + "; saw " + all.stream().map(ServiceEvent::type).toList());
            return null;
        }
        int port() throws Exception { return await(ServiceEventType.SERVER_SOCKET_OPEN).localAddress().getPort(); }
        long count(ServiceEventType type) { return all.stream().filter(e -> e.type() == type).count(); }
    }
    static Socket connect(int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(LOOPBACK, port), 2000);
        socket.setSoTimeout(3000);
        return socket;
    }
    static <E extends Enum<E> & MockScenario> MockServerExecutor start(E mock, MockOptions options, Events events) {
        var server = new MockServerExecutor();
        server.addMock(mock, LOOPBACK, 0, options, events);
        server.start();
        return server;
    }

    @Test void waitsWithoutReplyAndTerminatesOnHalfCloseWithoutRetainingRequest() throws Exception {
        Events events = new Events();
        try (var server = start(MockCase.WAIT_FOR_CLIENT_DISCONNECT, MockOptions.defaults(), events);
             var socket = connect(events.port())) {
            socket.setSoTimeout(120);
            assertThrows(SocketTimeoutException.class, () -> socket.getInputStream().read());
            byte[] data = new byte[8193];
            socket.getOutputStream().write(data);
            socket.shutdownOutput();
            socket.setSoTimeout(3000);
            assertEquals(-1, socket.getInputStream().read());
            var ended = events.await(ServiceEventType.CLIENT_DISCONNECTED);
            assertEquals(TerminationReason.PEER_EOF, ended.reason());
            assertEquals(8193, ended.totalBytes());
            assertEquals(1, events.count(ServiceEventType.CLIENT_DISCONNECTED));
            assertEquals(0, events.count(ServiceEventType.RESPONSE_SEND));
        }
    }

    @Test void slowReadDelaysNextChunkAndReadsEveryByte() throws Exception {
        Events events = new Events();
        var options = MockOptions.builder().readChunkSize(2).readInterval(Duration.ofMillis(120)).build();
        try (var server = start(MockCase.SLOW_READ, options, events); var socket = connect(events.port())) {
            socket.getOutputStream().write(new byte[]{1,2,3,4,5,6});
            socket.shutdownOutput();
            events.await(ServiceEventType.CLIENT_DISCONNECTED);
            var reads = events.all.stream().filter(e -> e.type() == ServiceEventType.REQUEST_RECEIVED).toList();
            assertTrue(reads.size() >= 3);
            assertEquals(6, reads.stream().mapToInt(ServiceEvent::bytes).sum());
            assertTrue(reads.stream().allMatch(e -> e.bytes() <= 2));
            assertTrue(Duration.between(reads.get(0).timestamp(), reads.get(2).timestamp()).toMillis() >= 180);
        }
    }

    @Test void responseActionWritesHttpWithByteLengthAndOrderedEvents() throws Exception {
        Events events = new Events();
        try (var server = start(Cases.RESPONSE, MockOptions.defaults(), events); var socket = connect(events.port())) {
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Length: 3\r\nConnection: close\r\n\r\n가", response);
            events.await(ServiceEventType.CLIENT_DISCONNECTED);
            var types = events.all.stream().map(ServiceEvent::type).toList();
            assertTrue(types.indexOf(ServiceEventType.RESPONSE_SEND) < types.indexOf(ServiceEventType.RESPONSE_SENT));
            assertEquals(TerminationReason.RESPONSE_COMPLETED, events.all.get(events.all.size()-1).reason());
            assertTrue(events.all.stream().anyMatch(e -> e.exception() instanceof SendResponseException && e.action()));
            assertEquals(0, events.count(ServiceEventType.POINT_EXIT));
        }
    }

    @Test void closeDelayWaitsBeforeClosing() throws Exception {
        Events events = new Events();
        try (var server = start(Cases.DELAY_CLOSE, MockOptions.defaults(), events); var socket = connect(events.port())) {
            events.await(ServiceEventType.EXCEPTION_RECEIVED);
            socket.setSoTimeout(70);
            assertThrows(SocketTimeoutException.class, () -> socket.getInputStream().read());
            socket.setSoTimeout(3000);
            assertEquals(-1, socket.getInputStream().read());
            assertEquals(TerminationReason.ACTION_CLOSE, events.await(ServiceEventType.CLIENT_DISCONNECTED).reason());
        }
    }

    @Test void ordinaryPointFailureClosesOnlyThatClientAndContinuesAccepting() throws Exception {
        Events events = new Events();
        try (var server = start(Cases.FAILURE, MockOptions.defaults(), events)) {
            int port = events.port();
            for (int i = 0; i < 2; i++) {
                try (var socket = connect(port)) { assertEquals(-1, socket.getInputStream().read()); }
                assertEquals(TerminationReason.ERROR, events.await(ServiceEventType.CLIENT_DISCONNECTED).reason());
            }
            assertEquals(2, events.count(ServiceEventType.EXCEPTION_RECEIVED));
            assertEquals(0, events.count(ServiceEventType.RESPONSE_SEND));
        }
    }

    @Test void listenerExceptionsNeverRollbackStartOrCloseClient() throws Exception {
        Events events = new Events();
        var failures = new AtomicInteger();
        var chain = ServiceStatusListenerChain.of(e -> { failures.incrementAndGet(); throw new IllegalStateException("listener"); }, events);
        try (var server = new MockServerExecutor()) {
            server.addMock(MockCase.WAIT_FOR_CLIENT_DISCONNECT, LOOPBACK, 0, chain);
            assertDoesNotThrow(server::start);
            try (var socket = connect(events.port())) {
                socket.setSoTimeout(100);
                assertThrows(SocketTimeoutException.class, () -> socket.getInputStream().read());
                socket.shutdownOutput();
                events.await(ServiceEventType.CLIENT_DISCONNECTED);
            }
            assertDoesNotThrow(server::stop);
            assertEquals(failures.get(), events.all.size());
            assertEquals(0, events.count(ServiceEventType.EXCEPTION_RECEIVED));
            assertEquals(1, events.count(ServiceEventType.SERVICE_STOP));
        }
    }

    @Test void stopReleasesBlockedReadAndLongCloseDelay() throws Exception {
        Events waiting = new Events();
        Events delayed = new Events();
        try (var server = new MockServerExecutor()) {
            server.addMock(MockCase.WAIT_FOR_CLIENT_DISCONNECT, LOOPBACK, 0, waiting);
            server.addMock(Cases.LONG_CLOSE, LOOPBACK, 0, delayed);
            server.start();
            try (var a = connect(waiting.port()); var b = connect(delayed.port())) {
                waiting.await(ServiceEventType.CLIENT_CONNECTED);
                delayed.await(ServiceEventType.EXCEPTION_RECEIVED);
                long before = System.nanoTime();
                server.stop();
                assertTrue(System.nanoTime() - before < TimeUnit.SECONDS.toNanos(3));
                assertEquals(-1, a.getInputStream().read());
                assertEquals(-1, b.getInputStream().read());
                assertEquals(TerminationReason.STOP, waiting.await(ServiceEventType.CLIENT_DISCONNECTED).reason());
                assertEquals(TerminationReason.STOP, delayed.await(ServiceEventType.CLIENT_DISCONNECTED).reason());
                assertEquals(0, waiting.count(ServiceEventType.EXCEPTION_RECEIVED));
            }
            server.stop();
            assertThrows(IllegalStateException.class, server::start);
        }
    }

    @Test void failedSecondBindReleasesFirstBinding() throws Exception {
        Events events = new Events();
        try (var occupied = new ServerSocket(0, 50, LOOPBACK); var server = new MockServerExecutor()) {
            server.addMock(MockCase.SLOW_READ, LOOPBACK, 0, events);
            server.addMock(MockCase.SLOW_READ, LOOPBACK, occupied.getLocalPort(), events);
            assertThrows(MockServerStartException.class, server::start);
            int firstPort = events.port();
            try (var rebound = new ServerSocket(firstPort, 50, LOOPBACK)) {
                assertEquals(firstPort, rebound.getLocalPort());
            }
        }
    }

    @Test void capacityRejectsExcessClientWithoutQueueing() throws Exception {
        Events events = new Events();
        try (var server = new MockServerExecutor(new ServerOptions(1, Duration.ofSeconds(3)))) {
            server.addMock(MockCase.WAIT_FOR_CLIENT_DISCONNECT, LOOPBACK, 0, events);
            server.start();
            int port = events.port();
            try (var first = connect(port)) {
                events.await(ServiceEventType.POINT_ENTER);
                try (var second = connect(port)) {
                    assertEquals(-1, second.getInputStream().read());
                    assertEquals(TerminationReason.CAPACITY_REJECTED, events.await(ServiceEventType.CLIENT_DISCONNECTED).reason());
                }
                first.setSoTimeout(100);
                assertThrows(SocketTimeoutException.class, () -> first.getInputStream().read());
            }
        }
    }

    @Test void writeDeadlineClosesNonReadingClientWithoutSentEvent() throws Exception {
        Events events = new Events();
        var options = MockOptions.builder().sendBufferSize(1024).responseTimeout(Duration.ofMillis(150)).build();
        try (var server = start(Cases.HUGE_RESPONSE, options, events); var socket = new Socket()) {
            socket.setReceiveBufferSize(1024);
            socket.connect(new InetSocketAddress(LOOPBACK, events.port()), 2000);
            var ended = events.await(ServiceEventType.CLIENT_DISCONNECTED);
            assertEquals(TerminationReason.ERROR, ended.reason());
            assertEquals(1, events.count(ServiceEventType.RESPONSE_SEND));
            assertEquals(0, events.count(ServiceEventType.RESPONSE_SENT));
            assertTrue(events.all.stream().anyMatch(e -> e.exception() instanceof SocketTimeoutException));
        }
    }

    @Test void unknownActionClosesClientInsteadOfContinuing() throws Exception {
        Events events = new Events();
        try (var server = start(Cases.UNKNOWN_ACTION, MockOptions.defaults(), events); var socket = connect(events.port())) {
            assertEquals(-1, socket.getInputStream().read());
            assertEquals(TerminationReason.ERROR, events.await(ServiceEventType.CLIENT_DISCONNECTED).reason());
            assertEquals(2, events.count(ServiceEventType.EXCEPTION_RECEIVED));
        }
    }

    @Test void lifecycleRejectsInvalidRegistrationsAndCallbackReentry() throws Exception {
        Events events = new Events();
        try (var server = new MockServerExecutor()) {
            assertThrows(IllegalStateException.class, server::start);
            assertThrows(IllegalArgumentException.class, () -> server.addMock(MockCase.SLOW_READ, LOOPBACK, -1, events));
            assertThrows(NullPointerException.class, () -> server.addMock(MockCase.SLOW_READ, LOOPBACK, 0, null));
            var reentries = new AtomicInteger();
            server.addMock(MockCase.SLOW_READ, LOOPBACK, 0, ServiceStatusListenerChain.of(e -> {
                assertThrows(IllegalStateException.class, server::stop);
                reentries.incrementAndGet();
            }, events));
            server.start();
            server.start();
            assertEquals(1, events.count(ServiceEventType.SERVICE_START));
            assertThrows(IllegalStateException.class, () -> server.addMock(MockCase.SLOW_READ, LOOPBACK, 0, events));
            server.stop();
            assertTrue(reentries.get() >= 4);
        }
    }

    @Test void readTimeoutIsReportedAndActualSocketOptionsAreObservable() throws Exception {
        Events events = new Events();
        var options = MockOptions.builder().receiveBufferSize(4096).sendBufferSize(4096)
            .lingerSeconds(0).keepAlive(true).tcpNoDelay(false).readTimeoutMillis(80).build();
        try (var server = start(MockCase.WAIT_FOR_CLIENT_DISCONNECT, options, events);
             var socket = connect(events.port())) {
            var point = events.await(ServiceEventType.POINT_ENTER);
            assertEquals(0, point.socketOptions().get("lingerSeconds"));
            assertEquals(true, point.socketOptions().get("keepAlive"));
            assertEquals(false, point.socketOptions().get("tcpNoDelay"));
            assertEquals(80, point.socketOptions().get("readTimeoutMillis"));
            assertTrue((Integer) point.socketOptions().get("receiveBufferSize") > 0);
            var exception = events.await(ServiceEventType.EXCEPTION_RECEIVED);
            assertInstanceOf(SocketTimeoutException.class, exception.exception());
            assertFalse(exception.action());
            assertEquals(TerminationReason.ERROR, events.await(ServiceEventType.CLIENT_DISCONNECTED).reason());
        }
    }

    @Test void shutdownTimeoutDoesNotClaimSuccessWhileListenerIsStillExecuting() throws Exception {
        Events events = new Events();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var server = new MockServerExecutor(new ServerOptions(2, Duration.ofMillis(100)));
        ServiceStatusListener blocker = e -> {
            if (e.type() == ServiceEventType.CLIENT_CONNECTED) {
                entered.countDown();
                boolean interrupted = false;
                while (true) {
                    try { release.await(); break; }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
        };
        try {
            server.addMock(MockCase.SLOW_READ, LOOPBACK, 0, ServiceStatusListenerChain.of(blocker, events));
            server.start();
            try (var socket = connect(events.port())) {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertThrows(MockServerStopException.class, server::stop);
                assertEquals(0, events.count(ServiceEventType.SERVICE_STOP));
                release.countDown();
                events.await(ServiceEventType.SERVICE_STOP);
                server.stop();
                assertEquals(1, events.count(ServiceEventType.CLIENT_DISCONNECTED));
            }
        } finally { release.countDown(); server.stop(); }
    }

    @Test void interruptedStopRequestsCleanupAndRestoresCallerInterrupt() throws Exception {
        Events events = new Events();
        try (var server = start(MockCase.SLOW_READ, MockOptions.defaults(), events);
             var socket = connect(events.port())) {
            events.await(ServiceEventType.POINT_ENTER);
            Thread.currentThread().interrupt();
            try {
                assertThrows(MockServerStopException.class, server::stop);
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
            server.stop();
            assertEquals(1, events.count(ServiceEventType.SERVICE_STOP));
            assertEquals(-1, socket.getInputStream().read());
        }
    }

    @Test void stopCancelsBlockedResponseWriteBeforeItsOwnDeadline() throws Exception {
        Events events = new Events();
        var options = MockOptions.builder().sendBufferSize(1024).responseTimeout(Duration.ofSeconds(30)).build();
        try (var server = start(Cases.HUGE_RESPONSE, options, events); var socket = new Socket()) {
            socket.setReceiveBufferSize(1024);
            socket.connect(new InetSocketAddress(LOOPBACK, events.port()), 2000);
            events.await(ServiceEventType.RESPONSE_SEND);
            server.stop();
            assertEquals(0, events.count(ServiceEventType.RESPONSE_SENT));
            assertEquals(TerminationReason.STOP, events.await(ServiceEventType.CLIENT_DISCONNECTED).reason());
        }
    }

    @Test void concurrentConnectAndStopLeavesNoDuplicateDisconnectOrThreads() throws Exception {
        Events events = new Events();
        ExecutorService callers = Executors.newFixedThreadPool(8);
        List<Future<?>> attempts = new ArrayList<>();
        var gate = new CountDownLatch(1);
        String workerPrefix = "http-mock-";
        Set<Long> before = new HashSet<>();
        Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().startsWith(workerPrefix))
            .forEach(t -> before.add(t.getId()));
        try (var server = start(MockCase.WAIT_FOR_CLIENT_DISCONNECT, MockOptions.defaults(), events)) {
            int port = events.port();
            for (int i = 0; i < 30; i++) attempts.add(callers.submit(() -> {
                try {
                    gate.await();
                    try (var socket = connect(port)) { socket.shutdownOutput(); }
                } catch (IOException ignored) { /* stop may win the connect race */ }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }));
            gate.countDown();
            server.stop();
            for (var future : attempts) future.get(3, TimeUnit.SECONDS);
            var connected = events.all.stream().filter(e -> e.type() == ServiceEventType.CLIENT_CONNECTED)
                .map(ServiceEvent::clientId).toList();
            var disconnected = events.all.stream().filter(e -> e.type() == ServiceEventType.CLIENT_DISCONNECTED)
                .map(ServiceEvent::clientId).toList();
            assertEquals(new HashSet<>(connected), new HashSet<>(disconnected));
            assertEquals(disconnected.size(), new HashSet<>(disconnected).size());
            assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t ->
                t.isAlive() && t.getName().startsWith(workerPrefix) && !before.contains(t.getId())));
        } finally { gate.countDown(); callers.shutdownNow(); assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS)); }
    }

    @Test void stopDeadlineIncludesBlockedStartupAndRequestsCleanup() throws Exception {
        Events events = new Events();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        var server = new MockServerExecutor(new ServerOptions(2, Duration.ofMillis(80)));
        Future<?> stopping = null;
        Future<?> starting = null;
        try {
            server.addMock(MockCase.SLOW_READ, LOOPBACK, 0, events);
            server.addMock(MockCase.SLOW_READ, LOOPBACK, 0, e -> {
                if (e.type() == ServiceEventType.SERVICE_START) {
                    entered.countDown();
                    try { release.await(); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                }
            });
            starting = callers.submit(server::start);
            int firstPort = events.port();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            stopping = callers.submit(server::stop);
            Future<?> result = stopping;
            var failure = assertThrows(ExecutionException.class, () -> result.get(1, TimeUnit.SECONDS));
            assertInstanceOf(MockServerStopException.class, failure.getCause());
            try (var rebound = new ServerSocket(firstPort, 50, LOOPBACK)) {
                assertEquals(firstPort, rebound.getLocalPort());
            }
            assertEquals(0, events.count(ServiceEventType.SERVICE_STOP));
            release.countDown();
            Future<?> startup = starting;
            assertThrows(ExecutionException.class, () -> startup.get(2, TimeUnit.SECONDS));
            events.await(ServiceEventType.SERVICE_STOP);
            server.stop();
        } finally {
            release.countDown();
            callers.shutdown();
            assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS));
            server.stop();
        }
    }

    @Test void responseSuccessWinsAgainstLaterStopButResourcesStillClose() throws Exception {
        Events events = new Events();
        CountDownLatch sent = new CountDownLatch(1), release = new CountDownLatch(1);
        var server = new MockServerExecutor(new ServerOptions(2, Duration.ofMillis(80)));
        try {
            server.addMock(Cases.RESPONSE, LOOPBACK, 0, ServiceStatusListenerChain.of(events, e -> {
                if (e.type() == ServiceEventType.RESPONSE_SENT) {
                    sent.countDown();
                    while (true) {
                        try { release.await(); break; }
                        catch (InterruptedException ignored) { /* model slow user callback */ }
                    }
                }
            }));
            server.start();
            try (var socket = connect(events.port())) {
                assertTrue(sent.await(2, TimeUnit.SECONDS));
                assertThrows(MockServerStopException.class, server::stop);
                release.countDown();
                var end = events.await(ServiceEventType.CLIENT_DISCONNECTED);
                assertEquals(TerminationReason.RESPONSE_COMPLETED, end.reason());
                server.stop();
                assertTrue(new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8).endsWith("가"));
            }
        } finally { release.countDown(); server.stop(); }
    }

    @Test void interruptedStopDuringStartupStillRequestsCleanup() throws Exception {
        Events events = new Events();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService starter = Executors.newSingleThreadExecutor();
        var server = new MockServerExecutor();
        try {
            server.addMock(MockCase.SLOW_READ, LOOPBACK, 0, events);
            server.addMock(MockCase.SLOW_READ, LOOPBACK, 0, e -> {
                if (e.type() == ServiceEventType.SERVICE_START) {
                    entered.countDown();
                    try { release.await(); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                }
            });
            Future<?> starting = starter.submit(server::start);
            int port = events.port();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Thread.currentThread().interrupt();
            try {
                assertThrows(MockServerStopException.class, server::stop);
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
            release.countDown();
            assertThrows(ExecutionException.class, () -> starting.get(2, TimeUnit.SECONDS));
            server.stop();
            try (var rebound = new ServerSocket(port, 50, LOOPBACK)) { assertEquals(port, rebound.getLocalPort()); }
            assertEquals(1, events.count(ServiceEventType.SERVICE_STOP));
        } finally {
            release.countDown(); starter.shutdown();
            assertTrue(starter.awaitTermination(3, TimeUnit.SECONDS)); server.stop();
        }
    }
}
