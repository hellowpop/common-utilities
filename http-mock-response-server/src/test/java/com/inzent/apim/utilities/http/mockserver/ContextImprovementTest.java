package com.inzent.apim.utilities.http.mockserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class ContextImprovementTest {
    enum Point { OUTER, INNER, READ }
    enum Scenario implements MockScenario {
        NESTED {
            public void execute(MockClientContext clientContext) throws Exception {
                assertNull(clientContext.point());
                clientContext.runPoint(Point.OUTER, () -> {
                    assertSame(Point.OUTER, clientContext.point());
                    clientContext.runPoint(Point.INNER, () -> assertSame(Point.INNER, clientContext.point()));
                    assertSame(Point.OUTER, clientContext.point());
                    clientContext.runPoint(MockPoint.ON_CONNECTED);
                    assertSame(Point.OUTER, clientContext.point());
                    try {
                        clientContext.runPoint(Point.INNER, () -> { throw new IOException("nested"); });
                    } catch (IOException expected) { assertSame(Point.OUTER, clientContext.point()); }
                });
                assertNull(clientContext.point());
                throw new CloseClientException();
            }
        },
        HTTP {
            public void execute(MockClientContext clientContext) throws Exception {
                assertEquals(HttpReceiveState.NOT_REACHED, clientContext.httpProtocol().request().initialLineState());
                clientContext.runPoint(Point.READ, () -> {
                    HttpProtocolResult result;
                    do {
                        clientContext.runPoint(MockPoint.READ_HTTP_PROTOCOL);
                        assertSame(Point.READ, clientContext.point());
                        result = clientContext.httpProtocol();
                    } while (!result.endOfInput());
                    RECEIVED.set(result);
                    assertEquals(clientContext.totalBytes(), WIRE.length());
                    throw new SendResponseException(MockResponse.utf8(200, "text/plain", "ok"));
                });
            }
        },
        RAW_THEN_HTTP {
            public void execute(MockClientContext clientContext) throws Exception {
                clientContext.runPoint(MockPoint.READ_CHUNK);
                assertThrows(IllegalStateException.class, () -> clientContext.runPoint(MockPoint.READ_HTTP_PROTOCOL));
                throw new CloseClientException();
            }
        },
        HTTP_THEN_RAW {
            public void execute(MockClientContext clientContext) throws Exception {
                clientContext.runPoint(MockPoint.READ_HTTP_PROTOCOL);
                assertThrows(IllegalStateException.class, () -> clientContext.runPoint(MockPoint.READ_CHUNK));
                throw new CloseClientException();
            }
        },
        FAILED_DECODER {
            public void execute(MockClientContext clientContext) throws Exception {
                assertThrows(IOException.class, clientContext::readHttpProtocol);
                assertThrows(IllegalStateException.class, clientContext::readHttpProtocol);
                throw new CloseClientException();
            }
        },
        CUSTOM_LIMITS {
            public void execute(MockClientContext clientContext) throws Exception {
                var limits = new HttpProtocolLimits(100, 100, 3);
                HttpProtocolResult result;
                do { result = clientContext.readHttpProtocol(limits); } while (!result.request().complete());
                assertEquals(3, result.request().content().length);
                assertThrows(IllegalStateException.class,
                    () -> clientContext.readHttpProtocol(new HttpProtocolLimits(100, 100, 4)));
                assertThrows(NullPointerException.class, () -> clientContext.runPoint(null, () -> {}));
                assertThrows(NullPointerException.class, () -> clientContext.runPoint(Point.READ, null));
                assertThrows(NullPointerException.class, () -> clientContext.runPoint((MockPoint) null));
                throw new CloseClientException();
            }
        },
        BLOCKED_HTTP {
            public void execute(MockClientContext clientContext) throws Exception {
                clientContext.runPoint(MockPoint.READ_HTTP_PROTOCOL);
            }
        }
    }
    static final AtomicReference<HttpProtocolResult> RECEIVED = new AtomicReference<>();
    static final String WIRE = "POST /demo HTTP/1.1\r\nHost: local\r\nContent-Length: 3\r\n\r\nabc";

    @Test void enumIdentitySurvivesEventsAndNestedPointRestoresEvenAfterFailure() throws Exception {
        var events = new ServerIntegrationTest.Events();
        try (var server = ServerIntegrationTest.start(Scenario.NESTED, MockOptions.defaults(), events);
             Socket socket = ServerIntegrationTest.connect(events.port())) {
            assertEquals(-1, socket.getInputStream().read());
            events.await(ServiceEventType.CLIENT_DISCONNECTED);
            assertEquals(List.of(Point.OUTER, Point.INNER, MockPoint.ON_CONNECTED, Point.INNER), events.all.stream()
                .filter(e -> e.type() == ServiceEventType.POINT_ENTER).map(ServiceEvent::point).toList());
            assertEquals(1, events.all.stream().filter(e -> e.type() == ServiceEventType.POINT_EXIT
                && e.point() == MockPoint.ON_CONNECTED).count());
            assertFalse(events.all.stream().anyMatch(e -> e.reason() == TerminationReason.ERROR));
        }
    }

    @Test void protocolReadAccumulatesOneByteReadsAndKeepsSnapshotAtEof() throws Exception {
        RECEIVED.set(null);
        var events = new ServerIntegrationTest.Events();
        try (var server = ServerIntegrationTest.start(Scenario.HTTP,
                MockOptions.builder().readChunkSize(1).build(), events);
             Socket socket = ServerIntegrationTest.connect(events.port())) {
            socket.getOutputStream().write(WIRE.getBytes(StandardCharsets.US_ASCII));
            socket.shutdownOutput();
            assertTrue(new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8).endsWith("ok"));
            assertNotNull(RECEIVED.get());
            assertTrue(RECEIVED.get().request().complete());
            assertArrayEquals(new byte[]{97, 98, 99}, RECEIVED.get().request().content());
        }
    }

    @Test void rawAndHttpReadsCannotSilentlyLoseEachOthersBytes() throws Exception {
        for (Scenario scenario : List.of(Scenario.RAW_THEN_HTTP, Scenario.HTTP_THEN_RAW)) {
            var events = new ServerIntegrationTest.Events();
            try (var server = ServerIntegrationTest.start(scenario, MockOptions.defaults(), events);
                 Socket socket = ServerIntegrationTest.connect(events.port())) {
                socket.getOutputStream().write(WIRE.getBytes(StandardCharsets.US_ASCII));
                socket.shutdownOutput();
                assertEquals(-1, socket.getInputStream().read());
                var end = events.await(ServiceEventType.CLIENT_DISCONNECTED);
                assertEquals(TerminationReason.ACTION_CLOSE, end.reason());
            }
        }
    }

    @Test void stopCancelsBlockedProtocolRead() throws Exception {
        var events = new ServerIntegrationTest.Events();
        try (var server = ServerIntegrationTest.start(Scenario.BLOCKED_HTTP, MockOptions.defaults(), events);
             Socket socket = ServerIntegrationTest.connect(events.port())) {
            events.await(ServiceEventType.POINT_ENTER);
            server.stop();
            assertEquals(-1, socket.getInputStream().read());
            assertEquals(TerminationReason.STOP, events.all.stream()
                .filter(e -> e.type() == ServiceEventType.CLIENT_DISCONNECTED).findFirst().orElseThrow().reason());
        }
    }

    @Test void parserFailureRejectsAnotherReadBeforeTouchingSocket() throws Exception {
        var events = new ServerIntegrationTest.Events();
        try (var server = ServerIntegrationTest.start(Scenario.FAILED_DECODER,
                MockOptions.builder().readTimeoutMillis(100).build(), events);
             Socket socket = ServerIntegrationTest.connect(events.port())) {
            socket.getOutputStream().write("BAD\r\n".getBytes(StandardCharsets.US_ASCII));
            // Keep output open: another socket read would time out instead of failing immediately.
            assertEquals(-1, socket.getInputStream().read());
            assertEquals(TerminationReason.ACTION_CLOSE, events.await(ServiceEventType.CLIENT_DISCONNECTED).reason());
        }
    }

    @Test void configuredLimitsStayFixedAndNullPointsAreRejected() throws Exception {
        var events = new ServerIntegrationTest.Events();
        try (var server = ServerIntegrationTest.start(Scenario.CUSTOM_LIMITS,
                MockOptions.builder().readChunkSize(1).build(), events);
             Socket socket = ServerIntegrationTest.connect(events.port())) {
            socket.getOutputStream().write(WIRE.getBytes(StandardCharsets.US_ASCII));
            assertEquals(-1, socket.getInputStream().read());
            assertEquals(TerminationReason.ACTION_CLOSE, events.await(ServiceEventType.CLIENT_DISCONNECTED).reason());
        }
    }
}
