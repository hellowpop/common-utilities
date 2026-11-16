package com.inzent.apim.utilities.http.mockserver;

import org.junit.jupiter.api.Assertions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;


@Timeout(15)
class ServerIntegrationTest {
    enum Point { BROKEN, CLOSE, REPLY, UNKNOWN }
    static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    enum Cases implements MockScenario {
        RESPONSE {
            public void execute(MockClientContext clientContext) throws Exception {
                clientContext.runPoint(Point.REPLY, () -> { throw new SendResponseException(MockResponse.utf8(200, "text/plain; charset=UTF-8", "가")); });
                throw new AssertionError("action must not return");
            }
        },
        DELAY_CLOSE {
            public void execute(MockClientContext clientContext) throws Exception {
                clientContext.runPoint(Point.CLOSE, () -> { throw new CloseClientException(Duration.ofMillis(250)); });
            }
        },
        LONG_CLOSE {
            public void execute(MockClientContext clientContext) throws Exception {
                clientContext.runPoint(Point.CLOSE, () -> { throw new CloseClientException(Duration.ofSeconds(30)); });
            }
        },
        FAILURE {
            public void execute(MockClientContext clientContext) throws Exception {
                clientContext.runPoint(Point.BROKEN, () -> { throw new IOException("injected point failure"); });
                throw new AssertionError("must not run");
            }
        },
        HUGE_RESPONSE {
            public void execute(MockClientContext clientContext) throws Exception {
                clientContext.runPoint(Point.REPLY, () -> { throw new SendResponseException(new MockResponse(new byte[16 * 1024 * 1024])); });
            }
        },
        UNKNOWN_ACTION {
            public void execute(MockClientContext clientContext) throws Exception {
                clientContext.runPoint(Point.UNKNOWN, () -> { throw new NextActionException("unknown") {}; });
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
                com.inzent.apim.utilities.http.mockserver.ServiceEvent e = queue.poll(100, TimeUnit.MILLISECONDS);
                if (e != null && e.type() == type) return e;
            }
            Assertions.fail("Missing event " + type + "; saw " + all.stream().map(ServiceEvent::type).toList());
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
        com.inzent.apim.utilities.http.mockserver.MockServerExecutor server = new MockServerExecutor();
        server.addMock(mock, LOOPBACK, 0, options, events);
        server.start();
        return server;
    }

    @Test void waitsWithoutReplyAndTerminatesOnHalfCloseWithoutRetainingRequest() throws Exception {
        Events events = new Events();
        try (com.inzent.apim.utilities.http.mockserver.MockServerExecutor server = start(MockCase.WAIT_FOR_CLIENT_DISCONNECT, MockOptions.defaults(), events);
             java.net.Socket socket = connect(events.port())) {
            socket.setSoTimeout(120);
            Assertions.assertThrows(SocketTimeoutException.class, () -> socket.getInputStream().read());
            byte[] data = new byte[8193];
            socket.getOutputStream().write(data);
            socket.shutdownOutput();
            socket.setSoTimeout(3000);
            Assertions.assertEquals(-1, socket.getInputStream().read());
            com.inzent.apim.utilities.http.mockserver.ServiceEvent ended = events.await(ServiceEventType.CLIENT_DISCONNECTED);
            Assertions.assertEquals(TerminationReason.PEER_EOF, ended.reason());
            Assertions.assertEquals(8193, ended.totalBytes());
            Assertions.assertEquals(1, events.count(ServiceEventType.CLIENT_DISCONNECTED));
            Assertions.assertEquals(0, events.count(ServiceEventType.RESPONSE_SEND));
        }
    }

    @Test void slowReadDelaysNextChunkAndReadsEveryByte() throws Exception {
        Events events = new Events();
        com.inzent.apim.utilities.http.mockserver.MockOptions options = MockOptions.builder().readChunkSize(2).readInterval(Duration.ofMillis(120)).build();
        try (com.inzent.apim.utilities.http.mockserver.MockServerExecutor server = start(MockCase.SLOW_READ, options, events); java.net.Socket socket = connect(events.port())) {
            socket.getOutputStream().write(new byte[]{1,2,3,4,5,6});
            socket.shutdownOutput();
            events.await(ServiceEventType.CLIENT_DISCONNECTED);
            java.util.List<com.inzent.apim.utilities.http.mockserver.ServiceEvent> reads = events.all.stream().filter(e -> e.type() == ServiceEventType.REQUEST_RECEIVED).toList();
            Assertions.assertTrue(reads.size() >= 3);
            Assertions.assertEquals(6, reads.stream().mapToInt(ServiceEvent::bytes).sum());
            Assertions.assertTrue(reads.stream().allMatch(e -> e.bytes() <= 2));
            Assertions.assertTrue(Duration.between(reads.get(0).timestamp(), reads.get(2).timestamp()).toMillis() >= 180);
        }
    }

    @Test void responseActionWritesHttpWithByteLengthAndOrderedEvents() throws Exception {
        Events events = new Events();
        try (com.inzent.apim.utilities.http.mockserver.MockServerExecutor server = start(Cases.RESPONSE, MockOptions.defaults(), events); java.net.Socket socket = connect(events.port())) {
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Assertions.assertEquals("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Length: 3\r\nConnection: close\r\n\r\n가", response);
            events.await(ServiceEventType.CLIENT_DISCONNECTED);
            java.util.List<com.inzent.apim.utilities.http.mockserver.ServiceEventType> types = events.all.stream().map(ServiceEvent::type).toList();
            Assertions.assertTrue(types.indexOf(ServiceEventType.RESPONSE_SEND) < types.indexOf(ServiceEventType.RESPONSE_SENT));
            Assertions.assertEquals(TerminationReason.RESPONSE_COMPLETED, events.all.get(events.all.size()-1).reason());
            Assertions.assertTrue(events.all.stream().anyMatch(e -> e.exception() instanceof ThrowableSnapshot s && s.originalType().equals(SendResponseException.class.getName()) && e.action()));
            Assertions.assertEquals(0, events.count(ServiceEventType.POINT_EXIT));
        }
    }

    @Test void closeDelayWaitsBeforeClosing() throws Exception {
        Events events = new Events();
        try (com.inzent.apim.utilities.http.mockserver.MockServerExecutor server = start(Cases.DELAY_CLOSE, MockOptions.defaults(), events); java.net.Socket socket = connect(events.port())) {
            events.await(ServiceEventType.EXCEPTION_RECEIVED);
            socket.setSoTimeout(70);
            Assertions.assertThrows(SocketTimeoutException.class, () -> socket.getInputStream().read());
            socket.setSoTimeout(3000);
            Assertions.assertEquals(-1, socket.getInputStream().read());
            Assertions.assertEquals(TerminationReason.ACTION_CLOSE, events.await(ServiceEventType.CLIENT_DISCONNECTED).reason());
        }
    }

    @Test void ordinaryPointFailureClosesOnlyThatClientAndContinuesAccepting() throws Exception {
        Events events = new Events();
        try (com.inzent.apim.utilities.http.mockserver.MockServerExecutor server = start(Cases.FAILURE, MockOptions.defaults(), events)) {
            int port = events.port();
            for (int i = 0; i < 2; i++) {
                try (java.net.Socket socket = connect(port)) { Assertions.assertEquals(-1, socket.getInputStream().read()); }
                com.inzent.apim.utilities.http.mockserver.ServiceEvent ended = events.await(ServiceEventType.CLIENT_DISCONNECTED);
                Assertions.assertEquals(TerminationReason.ERROR, ended.reason());
                Assertions.assertSame(Point.BROKEN, ended.point());
            }
            Assertions.assertEquals(2, events.count(ServiceEventType.EXCEPTION_RECEIVED));
            Assertions.assertEquals(0, events.count(ServiceEventType.RESPONSE_SEND));
        }
    }

    @Test void listenerExceptionsNeverRollbackStartOrCloseClient() throws Exception {
        Events events = new Events();
        java.util.concurrent.atomic.AtomicInteger failures = new AtomicInteger();
        com.inzent.apim.utilities.http.mockserver.ServiceStatusListenerChain chain = ServiceStatusListenerChain.of(e -> { failures.incrementAndGet(); throw new IllegalStateException("listener"); }, events);
        try (com.inzent.apim.utilities.http.mockserver.MockServerExecutor server = new MockServerExecutor()) {
            server.addMock(MockCase.WAIT_FOR_CLIENT_DISCONNECT, LOOPBACK, 0, chain);
            Assertions.assertDoesNotThrow(server::start);
            try (java.net.Socket socket = connect(events.port())) {
                socket.setSoTimeout(100);
                Assertions.assertThrows(SocketTimeoutException.class, () -> socket.getInputStream().read());
                socket.shutdownOutput();
                events.await(ServiceEventType.CLIENT_DISCONNECTED);
            }
            Assertions.assertDoesNotThrow(server::stop);
            Assertions.assertEquals(failures.get(), events.all.size());
            Assertions.assertEquals(0, events.count(ServiceEventType.EXCEPTION_RECEIVED));
            Assertions.assertEquals(1, events.count(ServiceEventType.SERVICE_STOP));
        }
    }

    @Test void stopReleasesBlockedReadAndLongCloseDelay() throws Exception {
        Events waiting = new Events();
        Events delayed = new Events();
        try (com.inzent.apim.utilities.http.mockserver.MockServerExecutor server = new MockServerExecutor()) {
            server.addMock(MockCase.WAIT_FOR_CLIENT_DISCONNECT, LOOPBACK, 0, waiting);
            server.addMock(Cases.LONG_CLOSE, LOOPBACK, 0, delayed);
            server.start();
            try (java.net.Socket a = connect(waiting.port()); java.net.Socket b = connect(delayed.port())) {
                waiting.await(ServiceEventType.CLIENT_CONNECTED);
                delayed.await(ServiceEventType.EXCEPTION_RECEIVED);
                long before = System.nanoTime();
                server.stop();
                Assertions.assertTrue(System.nanoTime() - before < TimeUnit.SECONDS.toNanos(3));
                Assertions.assertEquals(-1, a.getInputStream().read());
                Assertions.assertEquals(-1, b.getInputStream().read());
                Assertions.assertEquals(TerminationReason.STOP, waiting.await(ServiceEventType.CLIENT_DISCONNECTED).reason());
                Assertions.assertEquals(TerminationReason.STOP, delayed.await(ServiceEventType.CLIENT_DISCONNECTED).reason());
                Assertions.assertEquals(0, waiting.count(ServiceEventType.EXCEPTION_RECEIVED));
            }
            server.stop();
            Assertions.assertThrows(IllegalStateException.class, server::start);
        }
    }

    @Test void failedSecondBindReleasesFirstBinding() throws Exception {
        Events events = new Events();
        try (java.net.ServerSocket occupied = new ServerSocket(0, 50, LOOPBACK); com.inzent.apim.utilities.http.mockserver.MockServerExecutor server = new MockServerExecutor()) {
            server.addMock(MockCase.SLOW_READ, LOOPBACK, 0, events);
            server.addMock(MockCase.SLOW_READ, LOOPBACK, occupied.getLocalPort(), events);
            Assertions.assertThrows(MockServerStartException.class, server::start);
            int firstPort = events.port();
            try (java.net.ServerSocket rebound = new ServerSocket(firstPort, 50, LOOPBACK)) {
                Assertions.assertEquals(firstPort, rebound.getLocalPort());
            }
        }
    }

    @Test void capacityRejectsExcessClientWithoutQueueing() throws Exception {
        Events events = new Events();
        try (com.inzent.apim.utilities.http.mockserver.MockServerExecutor server = new MockServerExecutor(new ServerOptions(1, Duration.ofSeconds(3)))) {
            server.addMock(MockCase.WAIT_FOR_CLIENT_DISCONNECT, LOOPBACK, 0, events);
            server.start();
            int port = events.port();
            try (java.net.Socket first = connect(port)) {
                events.await(ServiceEventType.POINT_ENTER);
                try (java.net.Socket second = connect(port)) {
                    Assertions.assertEquals(-1, second.getInputStream().read());
                    Assertions.assertEquals(TerminationReason.CAPACITY_REJECTED, events.await(ServiceEventType.CLIENT_DISCONNECTED).reason());
                }
                first.setSoTimeout(100);
                Assertions.assertThrows(SocketTimeoutException.class, () -> first.getInputStream().read());
            }
        }
    }

    @Test void writeDeadlineClosesNonReadingClientWithoutSentEvent() throws Exception {
        Events events = new Events();
        com.inzent.apim.utilities.http.mockserver.MockOptions options = MockOptions.builder().sendBufferSize(1024).responseTimeout(Duration.ofMillis(150)).build();
        try (com.inzent.apim.utilities.http.mockserver.MockServerExecutor server = start(Cases.HUGE_RESPONSE, options, events); java.net.Socket socket = new Socket()) {
            socket.setReceiveBufferSize(1024);
            socket.connect(new InetSocketAddress(LOOPBACK, events.port()), 2000);
            com.inzent.apim.utilities.http.mockserver.ServiceEvent ended = events.await(ServiceEventType.CLIENT_DISCONNECTED);
            Assertions.assertEquals(TerminationReason.ERROR, ended.reason());
            Assertions.assertEquals(1, events.count(ServiceEventType.RESPONSE_SEND));
            Assertions.assertEquals(0, events.count(ServiceEventType.RESPONSE_SENT));
            Assertions.assertTrue(events.all.stream().anyMatch(e -> e.exception() instanceof ThrowableSnapshot s && s.originalType().equals(SocketTimeoutException.class.getName())));
        }
    }

    @Test void unknownActionClosesClientInsteadOfContinuing() throws Exception {
        Events events = new Events();
        try (com.inzent.apim.utilities.http.mockserver.MockServerExecutor server = start(Cases.UNKNOWN_ACTION, MockOptions.defaults(), events); java.net.Socket socket = connect(events.port())) {
            Assertions.assertEquals(-1, socket.getInputStream().read());
            Assertions.assertEquals(TerminationReason.ERROR, events.await(ServiceEventType.CLIENT_DISCONNECTED).reason());
            Assertions.assertEquals(2, events.count(ServiceEventType.EXCEPTION_RECEIVED));
        }
    }

    @Test void lifecycleRejectsInvalidRegistrationsAndCallbackReentry() throws Exception {
        Events events = new Events();
        try (com.inzent.apim.utilities.http.mockserver.MockServerExecutor server = new MockServerExecutor()) {
            Assertions.assertThrows(IllegalStateException.class, server::start);
            Assertions.assertThrows(IllegalArgumentException.class, () -> server.addMock(MockCase.SLOW_READ, LOOPBACK, -1, events));
            Assertions.assertThrows(NullPointerException.class, () -> server.addMock(MockCase.SLOW_READ, LOOPBACK, 0, null));
            java.util.concurrent.atomic.AtomicInteger reentries = new AtomicInteger();
            server.addMock(MockCase.SLOW_READ, LOOPBACK, 0, ServiceStatusListenerChain.of(e -> {
                Assertions.assertThrows(IllegalStateException.class, server::stop);
                reentries.incrementAndGet();
            }, events));
            server.start();
            server.start();
            Assertions.assertEquals(1, events.count(ServiceEventType.SERVICE_START));
            Assertions.assertThrows(IllegalStateException.class, () -> server.addMock(MockCase.SLOW_READ, LOOPBACK, 0, events));
            server.stop();
            Assertions.assertTrue(reentries.get() >= 4);
        }
    }

    @Test void readTimeoutIsReportedAndActualSocketOptionsAreObservable() throws Exception {
        Events events = new Events();
        com.inzent.apim.utilities.http.mockserver.MockOptions options = MockOptions.builder().receiveBufferSize(4096).sendBufferSize(4096)
            .lingerSeconds(0).keepAlive(true).tcpNoDelay(false).readTimeoutMillis(80).build();
        try (com.inzent.apim.utilities.http.mockserver.MockServerExecutor server = start(MockCase.WAIT_FOR_CLIENT_DISCONNECT, options, events);
             java.net.Socket socket = connect(events.port())) {
            com.inzent.apim.utilities.http.mockserver.ServiceEvent point = events.await(ServiceEventType.POINT_ENTER);
            Assertions.assertEquals(0, point.socketOptions().get("lingerSeconds"));
            Assertions.assertEquals(true, point.socketOptions().get("keepAlive"));
            Assertions.assertEquals(false, point.socketOptions().get("tcpNoDelay"));
            Assertions.assertEquals(80, point.socketOptions().get("readTimeoutMillis"));
            Assertions.assertTrue((Integer) point.socketOptions().get("receiveBufferSize") > 0);
            com.inzent.apim.utilities.http.mockserver.ServiceEvent exception = events.await(ServiceEventType.EXCEPTION_RECEIVED);
            Assertions.assertEquals(SocketTimeoutException.class.getName(),
                Assertions.assertInstanceOf(ThrowableSnapshot.class, exception.exception()).originalType());
            Assertions.assertFalse(exception.action());
            Assertions.assertEquals(TerminationReason.ERROR, events.await(ServiceEventType.CLIENT_DISCONNECTED).reason());
        }
    }

    @Test void shutdownTimeoutDoesNotClaimSuccessWhileListenerIsStillExecuting() throws Exception {
        Events events = new Events();
        java.util.concurrent.CountDownLatch entered = new CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new CountDownLatch(1);
        com.inzent.apim.utilities.http.mockserver.MockServerExecutor server = new MockServerExecutor(new ServerOptions(2, Duration.ofMillis(100)));
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
            try (java.net.Socket socket = connect(events.port())) {
                Assertions.assertTrue(entered.await(2, TimeUnit.SECONDS));
                Assertions.assertThrows(MockServerStopException.class, server::stop);
                Assertions.assertEquals(0, events.count(ServiceEventType.SERVICE_STOP));
                release.countDown();
                events.await(ServiceEventType.SERVICE_STOP);
                server.stop();
                Assertions.assertEquals(1, events.count(ServiceEventType.CLIENT_DISCONNECTED));
            }
        } finally { release.countDown(); server.stop(); }
    }

    @Test void interruptedStopRequestsCleanupAndRestoresCallerInterrupt() throws Exception {
        Events events = new Events();
        try (com.inzent.apim.utilities.http.mockserver.MockServerExecutor server = start(MockCase.SLOW_READ, MockOptions.defaults(), events);
             java.net.Socket socket = connect(events.port())) {
            events.await(ServiceEventType.POINT_ENTER);
            Thread.currentThread().interrupt();
            try {
                Assertions.assertThrows(MockServerStopException.class, server::stop);
                Assertions.assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
            server.stop();
            Assertions.assertEquals(1, events.count(ServiceEventType.SERVICE_STOP));
            Assertions.assertEquals(-1, socket.getInputStream().read());
        }
    }

    @Test void stopCancelsBlockedResponseWriteBeforeItsOwnDeadline() throws Exception {
        Events events = new Events();
        com.inzent.apim.utilities.http.mockserver.MockOptions options = MockOptions.builder().sendBufferSize(1024).responseTimeout(Duration.ofSeconds(30)).build();
        try (com.inzent.apim.utilities.http.mockserver.MockServerExecutor server = start(Cases.HUGE_RESPONSE, options, events); java.net.Socket socket = new Socket()) {
            socket.setReceiveBufferSize(1024);
            socket.connect(new InetSocketAddress(LOOPBACK, events.port()), 2000);
            events.await(ServiceEventType.RESPONSE_SEND);
            server.stop();
            Assertions.assertEquals(0, events.count(ServiceEventType.RESPONSE_SENT));
            Assertions.assertEquals(TerminationReason.STOP, events.await(ServiceEventType.CLIENT_DISCONNECTED).reason());
        }
    }

    @Test void concurrentConnectAndStopLeavesNoDuplicateDisconnectOrThreads() throws Exception {
        Events events = new Events();
        ExecutorService callers = Executors.newFixedThreadPool(8);
        List<Future<?>> attempts = new ArrayList<>();
        java.util.concurrent.CountDownLatch gate = new CountDownLatch(1);
        String workerPrefix = "http-mock-";
        Set<Long> before = new HashSet<>();
        Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().startsWith(workerPrefix))
            .forEach(t -> before.add(t.getId()));
        try (com.inzent.apim.utilities.http.mockserver.MockServerExecutor server = start(MockCase.WAIT_FOR_CLIENT_DISCONNECT, MockOptions.defaults(), events)) {
            int port = events.port();
            for (int i = 0; i < 30; i++) attempts.add(callers.submit(() -> {
                try {
                    gate.await();
                    try (java.net.Socket socket = connect(port)) { socket.shutdownOutput(); }
                } catch (IOException ignored) { /* stop may win the connect race */ }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }));
            gate.countDown();
            server.stop();
            for (java.util.concurrent.Future<?> future : attempts) future.get(3, TimeUnit.SECONDS);
            java.util.List<java.lang.Long> connected = events.all.stream().filter(e -> e.type() == ServiceEventType.CLIENT_CONNECTED)
                .map(ServiceEvent::clientId).toList();
            java.util.List<java.lang.Long> disconnected = events.all.stream().filter(e -> e.type() == ServiceEventType.CLIENT_DISCONNECTED)
                .map(ServiceEvent::clientId).toList();
            Assertions.assertEquals(new HashSet<>(connected), new HashSet<>(disconnected));
            Assertions.assertEquals(disconnected.size(), new HashSet<>(disconnected).size());
            Assertions.assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t ->
                t.isAlive() && t.getName().startsWith(workerPrefix) && !before.contains(t.getId())));
        } finally { gate.countDown(); callers.shutdownNow(); Assertions.assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS)); }
    }

    @Test void stopDeadlineIncludesBlockedStartupAndRequestsCleanup() throws Exception {
        Events events = new Events();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        com.inzent.apim.utilities.http.mockserver.MockServerExecutor server = new MockServerExecutor(new ServerOptions(2, Duration.ofMillis(80)));
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
            Assertions.assertTrue(entered.await(2, TimeUnit.SECONDS));
            stopping = callers.submit(server::stop);
            Future<?> result = stopping;
            java.util.concurrent.ExecutionException failure = Assertions.assertThrows(ExecutionException.class, () -> result.get(1, TimeUnit.SECONDS));
            Assertions.assertInstanceOf(MockServerStopException.class, failure.getCause());
            try (java.net.ServerSocket rebound = new ServerSocket(firstPort, 50, LOOPBACK)) {
                Assertions.assertEquals(firstPort, rebound.getLocalPort());
            }
            Assertions.assertEquals(0, events.count(ServiceEventType.SERVICE_STOP));
            release.countDown();
            Future<?> startup = starting;
            Assertions.assertThrows(ExecutionException.class, () -> startup.get(2, TimeUnit.SECONDS));
            events.await(ServiceEventType.SERVICE_STOP);
            server.stop();
        } finally {
            release.countDown();
            callers.shutdown();
            Assertions.assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS));
            server.stop();
        }
    }

    @Test void responseSuccessWinsAgainstLaterStopButResourcesStillClose() throws Exception {
        Events events = new Events();
        CountDownLatch sent = new CountDownLatch(1), release = new CountDownLatch(1);
        com.inzent.apim.utilities.http.mockserver.MockServerExecutor server = new MockServerExecutor(new ServerOptions(2, Duration.ofMillis(80)));
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
            try (java.net.Socket socket = connect(events.port())) {
                Assertions.assertTrue(sent.await(2, TimeUnit.SECONDS));
                Assertions.assertThrows(MockServerStopException.class, server::stop);
                release.countDown();
                com.inzent.apim.utilities.http.mockserver.ServiceEvent end = events.await(ServiceEventType.CLIENT_DISCONNECTED);
                Assertions.assertEquals(TerminationReason.RESPONSE_COMPLETED, end.reason());
                server.stop();
                Assertions.assertTrue(new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8).endsWith("가"));
            }
        } finally { release.countDown(); server.stop(); }
    }

    @Test void interruptedStopDuringStartupStillRequestsCleanup() throws Exception {
        Events events = new Events();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService starter = Executors.newSingleThreadExecutor();
        com.inzent.apim.utilities.http.mockserver.MockServerExecutor server = new MockServerExecutor();
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
            Assertions.assertTrue(entered.await(2, TimeUnit.SECONDS));
            Thread.currentThread().interrupt();
            try {
                Assertions.assertThrows(MockServerStopException.class, server::stop);
                Assertions.assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
            release.countDown();
            Assertions.assertThrows(ExecutionException.class, () -> starting.get(2, TimeUnit.SECONDS));
            server.stop();
            try (java.net.ServerSocket rebound = new ServerSocket(port, 50, LOOPBACK)) { Assertions.assertEquals(port, rebound.getLocalPort()); }
            Assertions.assertEquals(1, events.count(ServiceEventType.SERVICE_STOP));
        } finally {
            release.countDown(); starter.shutdown();
            Assertions.assertTrue(starter.awaitTermination(3, TimeUnit.SECONDS)); server.stop();
        }
    }
}
