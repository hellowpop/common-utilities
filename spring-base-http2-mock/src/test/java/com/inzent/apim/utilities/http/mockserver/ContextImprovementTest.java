package com.inzent.apim.utilities.http.mockserver;

import org.junit.jupiter.api.Assertions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;


@Timeout(15)
class ContextImprovementTest {
    enum Point { OUTER, INNER, READ }
    enum Scenario implements MockScenario {
        NESTED {
            public void execute(MockClientContext clientContext) throws Exception {
                Assertions.assertNull(clientContext.point());
                clientContext.runPoint(Point.OUTER, () -> {
                    Assertions.assertSame(Point.OUTER, clientContext.point());
                    clientContext.runPoint(Point.INNER, () -> Assertions.assertSame(Point.INNER, clientContext.point()));
                    Assertions.assertSame(Point.OUTER, clientContext.point());
                    clientContext.runPoint(MockPoint.ON_CONNECTED);
                    Assertions.assertSame(Point.OUTER, clientContext.point());
                    try {
                        clientContext.runPoint(Point.INNER, () -> { throw new IOException("nested"); });
                    } catch (IOException expected) { Assertions.assertSame(Point.OUTER, clientContext.point()); }
                });
                Assertions.assertNull(clientContext.point());
                throw new CloseClientException();
            }
        },
        HTTP {
            public void execute(MockClientContext clientContext) throws Exception {
                Assertions.assertEquals(HttpReceiveState.NOT_REACHED, clientContext.httpProtocol().request().initialLineState());
                clientContext.runPoint(Point.READ, () -> {
                    HttpProtocolResult result;
                    do {
                        clientContext.runPoint(MockPoint.READ_HTTP_PROTOCOL);
                        Assertions.assertSame(Point.READ, clientContext.point());
                        result = clientContext.httpProtocol();
                    } while (!result.endOfInput());
                    RECEIVED.set(result);
                    Assertions.assertEquals(clientContext.totalBytes(), WIRE.length());
                    throw new SendResponseException(MockResponse.utf8(200, "text/plain", "ok"));
                });
            }
        },
        RAW_THEN_HTTP {
            public void execute(MockClientContext clientContext) throws Exception {
                clientContext.runPoint(MockPoint.READ_CHUNK);
                Assertions.assertThrows(IllegalStateException.class, () -> clientContext.runPoint(MockPoint.READ_HTTP_PROTOCOL));
                throw new CloseClientException();
            }
        },
        HTTP_THEN_RAW {
            public void execute(MockClientContext clientContext) throws Exception {
                clientContext.runPoint(MockPoint.READ_HTTP_PROTOCOL);
                Assertions.assertThrows(IllegalStateException.class, () -> clientContext.runPoint(MockPoint.READ_CHUNK));
                throw new CloseClientException();
            }
        },
        FAILED_DECODER {
            public void execute(MockClientContext clientContext) throws Exception {
                Assertions.assertThrows(IOException.class, clientContext::readHttpProtocol);
                Assertions.assertThrows(IllegalStateException.class, clientContext::readHttpProtocol);
                throw new CloseClientException();
            }
        },
        CUSTOM_LIMITS {
            public void execute(MockClientContext clientContext) throws Exception {
                com.inzent.apim.utilities.http.mockserver.HttpProtocolLimits limits = new HttpProtocolLimits(100, 100, 3);
                HttpProtocolResult result;
                do { result = clientContext.readHttpProtocol(limits); } while (!result.request().complete());
                Assertions.assertEquals(3, result.request().content().length);
                Assertions.assertThrows(IllegalStateException.class,
                    () -> clientContext.readHttpProtocol(new HttpProtocolLimits(100, 100, 4)));
                Assertions.assertThrows(NullPointerException.class, () -> clientContext.runPoint(null, () -> {}));
                Assertions.assertThrows(NullPointerException.class, () -> clientContext.runPoint(Point.READ, null));
                Assertions.assertThrows(NullPointerException.class, () -> clientContext.runPoint((MockPoint) null));
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
        com.inzent.apim.utilities.http.mockserver.ServerIntegrationTest.Events events = new ServerIntegrationTest.Events();
        try (com.inzent.apim.utilities.http.mockserver.MockServerExecutor server = ServerIntegrationTest.start(Scenario.NESTED, MockOptions.defaults(), events);
             Socket socket = ServerIntegrationTest.connect(events.port())) {
            Assertions.assertEquals(-1, socket.getInputStream().read());
            events.await(ServiceEventType.CLIENT_DISCONNECTED);
            Assertions.assertEquals(List.of(Point.OUTER, Point.INNER, MockPoint.ON_CONNECTED, Point.INNER), events.all.stream()
                .filter(e -> e.type() == ServiceEventType.POINT_ENTER).map(ServiceEvent::point).toList());
            Assertions.assertEquals(1, events.all.stream().filter(e -> e.type() == ServiceEventType.POINT_EXIT
                && e.point() == MockPoint.ON_CONNECTED).count());
            Assertions.assertFalse(events.all.stream().anyMatch(e -> e.reason() == TerminationReason.ERROR));
        }
    }

    @Test void protocolReadAccumulatesOneByteReadsAndKeepsSnapshotAtEof() throws Exception {
        RECEIVED.set(null);
        com.inzent.apim.utilities.http.mockserver.ServerIntegrationTest.Events events = new ServerIntegrationTest.Events();
        try (com.inzent.apim.utilities.http.mockserver.MockServerExecutor server = ServerIntegrationTest.start(Scenario.HTTP,
                MockOptions.builder().readChunkSize(1).build(), events);
             Socket socket = ServerIntegrationTest.connect(events.port())) {
            socket.getOutputStream().write(WIRE.getBytes(StandardCharsets.US_ASCII));
            socket.shutdownOutput();
            Assertions.assertTrue(new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8).endsWith("ok"));
            Assertions.assertNotNull(RECEIVED.get());
            Assertions.assertTrue(RECEIVED.get().request().complete());
            Assertions.assertArrayEquals(new byte[]{97, 98, 99}, RECEIVED.get().request().content());
        }
    }

    @Test void rawAndHttpReadsCannotSilentlyLoseEachOthersBytes() throws Exception {
        for (Scenario scenario : List.of(Scenario.RAW_THEN_HTTP, Scenario.HTTP_THEN_RAW)) {
            com.inzent.apim.utilities.http.mockserver.ServerIntegrationTest.Events events = new ServerIntegrationTest.Events();
            try (com.inzent.apim.utilities.http.mockserver.MockServerExecutor server = ServerIntegrationTest.start(scenario, MockOptions.defaults(), events);
                 Socket socket = ServerIntegrationTest.connect(events.port())) {
                socket.getOutputStream().write(WIRE.getBytes(StandardCharsets.US_ASCII));
                socket.shutdownOutput();
                Assertions.assertEquals(-1, socket.getInputStream().read());
                com.inzent.apim.utilities.http.mockserver.ServiceEvent end = events.await(ServiceEventType.CLIENT_DISCONNECTED);
                Assertions.assertEquals(TerminationReason.ACTION_CLOSE, end.reason());
            }
        }
    }

    @Test void stopCancelsBlockedProtocolRead() throws Exception {
        com.inzent.apim.utilities.http.mockserver.ServerIntegrationTest.Events events = new ServerIntegrationTest.Events();
        try (com.inzent.apim.utilities.http.mockserver.MockServerExecutor server = ServerIntegrationTest.start(Scenario.BLOCKED_HTTP, MockOptions.defaults(), events);
             Socket socket = ServerIntegrationTest.connect(events.port())) {
            events.await(ServiceEventType.POINT_ENTER);
            server.stop();
            Assertions.assertEquals(-1, socket.getInputStream().read());
            Assertions.assertEquals(TerminationReason.STOP, events.all.stream()
                .filter(e -> e.type() == ServiceEventType.CLIENT_DISCONNECTED).findFirst().orElseThrow().reason());
        }
    }

    @Test void parserFailureRejectsAnotherReadBeforeTouchingSocket() throws Exception {
        com.inzent.apim.utilities.http.mockserver.ServerIntegrationTest.Events events = new ServerIntegrationTest.Events();
        try (com.inzent.apim.utilities.http.mockserver.MockServerExecutor server = ServerIntegrationTest.start(Scenario.FAILED_DECODER,
                MockOptions.builder().readTimeoutMillis(100).build(), events);
             Socket socket = ServerIntegrationTest.connect(events.port())) {
            socket.getOutputStream().write("BAD\r\n".getBytes(StandardCharsets.US_ASCII));
            // Keep output open: another socket read would time out instead of failing immediately.
            Assertions.assertEquals(-1, socket.getInputStream().read());
            Assertions.assertEquals(TerminationReason.ACTION_CLOSE, events.await(ServiceEventType.CLIENT_DISCONNECTED).reason());
        }
    }

    @Test void configuredLimitsStayFixedAndNullPointsAreRejected() throws Exception {
        com.inzent.apim.utilities.http.mockserver.ServerIntegrationTest.Events events = new ServerIntegrationTest.Events();
        try (com.inzent.apim.utilities.http.mockserver.MockServerExecutor server = ServerIntegrationTest.start(Scenario.CUSTOM_LIMITS,
                MockOptions.builder().readChunkSize(1).build(), events);
             Socket socket = ServerIntegrationTest.connect(events.port())) {
            socket.getOutputStream().write(WIRE.getBytes(StandardCharsets.US_ASCII));
            Assertions.assertEquals(-1, socket.getInputStream().read());
            Assertions.assertEquals(TerminationReason.ACTION_CLOSE, events.await(ServiceEventType.CLIENT_DISCONNECTED).reason());
        }
    }
}
