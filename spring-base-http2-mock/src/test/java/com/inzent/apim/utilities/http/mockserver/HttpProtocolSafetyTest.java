package com.inzent.apim.utilities.http.mockserver;

import org.junit.jupiter.api.Assertions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;


@Timeout(15)
class HttpProtocolSafetyTest {
    private static final AtomicReference<MockClientContext> SAVED_CONTEXT = new AtomicReference<>();
    private static final AtomicReference<HttpRequestSnapshot> SAVED_REQUEST = new AtomicReference<>();
    private static CountDownLatch partialReceived;

    enum Scenario implements MockScenario {
        COMPLETE, INVALID, PARTIAL;
        public void execute(MockClientContext clientContext) throws Exception {
            SAVED_CONTEXT.set(clientContext);
            while (true) {
                clientContext.runPoint(MockPoint.READ_HTTP_PROTOCOL);
                HttpProtocolResult result = clientContext.httpProtocol();
                if (result.request().contentBytes() >= 1024) partialReceived.countDown();
                if (result.request().complete()) {
                    SAVED_REQUEST.set(result.request());
                    throw new CloseClientException();
                }
                if (result.endOfInput()) return;
            }
        }
    }

    @BeforeEach void prepare() { partialReceived = new CountDownLatch(1); }
    @AfterEach void forgetSavedObjects() { SAVED_CONTEXT.set(null); SAVED_REQUEST.set(null); }

    @Test void escapedContextReleasesHeavyReferencesButSavedSnapshotSurvivesCompletion() throws Exception {
        com.inzent.apim.utilities.http.mockserver.ServerIntegrationTest.Events events = new ServerIntegrationTest.Events();
        java.util.concurrent.atomic.AtomicBoolean observedBeforeRelease = new AtomicBoolean();
        try (com.inzent.apim.utilities.http.mockserver.MockServerExecutor server = new MockServerExecutor()) {
            server.addMock(Scenario.COMPLETE, ServerIntegrationTest.LOOPBACK, 0,
                MockOptions.builder().readChunkSize(8192).build(), ServiceStatusListenerChain.of(events, event -> {
                    if (event.type() == ServiceEventType.CLIENT_DISCONNECTED) {
                        Assertions.assertTrue(SAVED_CONTEXT.get().httpProtocol().request().complete());
                        Assertions.assertTrue(SAVED_CONTEXT.get().totalBytes() > 1024 * 1024);
                        observedBeforeRelease.set(true);
                    }
                }));
            server.start();
            try (Socket socket = ServerIntegrationTest.connect(events.port())) {
                socket.getOutputStream().write(bytes("POST / HTTP/1.1\r\nHost: local\r\nContent-Length: 1048576\r\n\r\n"));
                socket.getOutputStream().write(new byte[1024 * 1024]);
                Assertions.assertEquals(-1, socket.getInputStream().read());
            }
            server.stop();
            Assertions.assertTrue(observedBeforeRelease.get(), "context stays usable until terminal callback returns");
            assertReleased(SAVED_CONTEXT.get());
            HttpRequestSnapshot request = SAVED_REQUEST.get();
            Assertions.assertTrue(request.complete());
            Assertions.assertEquals(1048576, request.contentBytes());
            Assertions.assertEquals(1048576, request.content().length);
            Assertions.assertTrue(ResourceSafetyTest.sessions(server).isEmpty());
            Assertions.assertEquals(64, ((java.util.concurrent.Semaphore) ResourceSafetyTest.field(server, "slots")).availablePermits());
            java.util.concurrent.ScheduledThreadPoolExecutor scheduler = (java.util.concurrent.ScheduledThreadPoolExecutor) ResourceSafetyTest.field(server, "scheduler");
            Assertions.assertTrue(scheduler.getQueue().isEmpty());
        }
    }

    @Test void escapedContextAlsoReleasesPartialBodyAfterParserFailure() throws Exception {
        com.inzent.apim.utilities.http.mockserver.ServerIntegrationTest.Events events = new ServerIntegrationTest.Events();
        try (com.inzent.apim.utilities.http.mockserver.MockServerExecutor server = ServerIntegrationTest.start(Scenario.INVALID,
                MockOptions.builder().readChunkSize(1024).build(), events)) {
            try (Socket socket = ServerIntegrationTest.connect(events.port())) {
                socket.getOutputStream().write(bytes("POST / HTTP/1.1\r\nHost: local\r\nTransfer-Encoding: chunked\r\n\r\n2000\r\n"));
                socket.getOutputStream().write(new byte[8192]);
                socket.getOutputStream().write(bytes("\r\ng\r\n"));
                Assertions.assertEquals(-1, socket.getInputStream().read());
                Assertions.assertEquals(TerminationReason.ERROR, events.await(ServiceEventType.CLIENT_DISCONNECTED).reason());
            }
            server.stop();
            assertReleased(SAVED_CONTEXT.get());
        }
    }

    @Test void stopReleasesEscapedContextAfterPartialRequestReadUnblocks() throws Exception {
        com.inzent.apim.utilities.http.mockserver.ServerIntegrationTest.Events events = new ServerIntegrationTest.Events();
        try (com.inzent.apim.utilities.http.mockserver.MockServerExecutor server = ServerIntegrationTest.start(Scenario.PARTIAL, MockOptions.defaults(), events);
             Socket socket = ServerIntegrationTest.connect(events.port())) {
            socket.getOutputStream().write(bytes("POST / HTTP/1.1\r\nHost: local\r\nContent-Length: 2097152\r\n\r\n"));
            socket.getOutputStream().write(new byte[1024]);
            Assertions.assertTrue(partialReceived.await(3, TimeUnit.SECONDS));
            server.stop();
            Assertions.assertEquals(-1, socket.getInputStream().read());
            assertReleased(SAVED_CONTEXT.get());
            Assertions.assertEquals(TerminationReason.STOP, events.await(ServiceEventType.CLIENT_DISCONNECTED).reason());
        }
    }

    @Test void terminalListenerNpeDoesNotPreventNextListenerOrContextRelease() throws Exception {
        com.inzent.apim.utilities.http.mockserver.ServerIntegrationTest.Events events = new ServerIntegrationTest.Events();
        java.util.concurrent.atomic.AtomicBoolean nextListenerCalled = new AtomicBoolean();
        try (com.inzent.apim.utilities.http.mockserver.MockServerExecutor server = new MockServerExecutor()) {
            server.addMock(Scenario.COMPLETE, ServerIntegrationTest.LOOPBACK, 0,
                ServiceStatusListenerChain.of(event -> {
                    if (event.type() == ServiceEventType.CLIENT_DISCONNECTED)
                        throw new NullPointerException("injected terminal listener failure");
                }, event -> {
                    if (event.type() == ServiceEventType.CLIENT_DISCONNECTED) nextListenerCalled.set(true);
                }, events));
            server.start();
            try (Socket socket = ServerIntegrationTest.connect(events.port())) {
                socket.getOutputStream().write(bytes("POST / HTTP/1.1\r\nHost: local\r\nContent-Length: 1\r\n\r\na"));
                Assertions.assertEquals(-1, socket.getInputStream().read());
            }
            server.stop();
            Assertions.assertTrue(nextListenerCalled.get());
            assertReleased(SAVED_CONTEXT.get());
        }
    }

    private static void assertReleased(MockClientContext clientContext) throws Exception {
        Assertions.assertNotNull(clientContext);
        for (String field : List.of("session", "owner", "buffer", "decoder"))
            Assertions.assertNull(ResourceSafetyTest.field(clientContext, field), "ended context retains " + field);
        Assertions.assertThrows(IllegalStateException.class, clientContext::point);
        Assertions.assertThrows(IllegalStateException.class, clientContext::readChunk);
        Assertions.assertThrows(IllegalStateException.class, clientContext::readHttpProtocol);
        Assertions.assertThrows(IllegalStateException.class, clientContext::httpProtocol);
        Assertions.assertThrows(IllegalStateException.class, clientContext::chunk);
        Assertions.assertThrows(IllegalStateException.class, clientContext::options);
        Assertions.assertThrows(IllegalStateException.class, clientContext::totalBytes);
        Assertions.assertThrows(IllegalStateException.class, clientContext::lastRead);
        Assertions.assertThrows(IllegalStateException.class, clientContext::clientId);
        Assertions.assertThrows(IllegalStateException.class, clientContext::checkCancelled);
        Assertions.assertThrows(IllegalStateException.class, () -> clientContext.delay(java.time.Duration.ZERO));
        Assertions.assertThrows(IllegalStateException.class, () -> clientContext.runPoint(MockPoint.READ_CHUNK));
    }

    @Test void requiredNullInputsFailBeforeChangingDecoderState() throws Exception {
        com.inzent.apim.utilities.http.mockserver.HttpRequestDecoder decoder = new HttpRequestDecoder(HttpProtocolLimits.defaults());
        Assertions.assertThrows(NullPointerException.class, () -> new HttpRequestDecoder(null));
        Assertions.assertThrows(NullPointerException.class, () -> decoder.feed(null));
        Assertions.assertEquals(HttpReceiveState.NOT_REACHED, decoder.result().request().initialLineState());
        Assertions.assertTrue(decoder.feed(ByteBuffer.wrap(bytes("GET / HTTP/1.0\r\n\r\n"))).request().complete());
        com.inzent.apim.utilities.http.mockserver.HttpRequestSnapshot empty = HttpRequestSnapshot.empty();
        Assertions.assertThrows(NullPointerException.class, () -> new HttpProtocolResult(null, List.of(), false));
        Assertions.assertThrows(NullPointerException.class, () -> new HttpProtocolResult(empty, null, false));
        Assertions.assertThrows(NullPointerException.class, () -> new HttpProtocolResult(empty, java.util.Arrays.asList(empty, null), false));
        Assertions.assertThrows(NullPointerException.class, () -> snapshot(null, Map.of(), new byte[0], Map.of(), HttpReceiveState.COMPLETE));
        Assertions.assertThrows(NullPointerException.class, () -> snapshot("", null, new byte[0], Map.of(), HttpReceiveState.COMPLETE));
        Assertions.assertThrows(NullPointerException.class, () -> snapshot("", Map.of(), null, Map.of(), HttpReceiveState.COMPLETE));
        Assertions.assertThrows(NullPointerException.class, () -> snapshot("", Map.of(), new byte[0], null, HttpReceiveState.COMPLETE));
        Assertions.assertThrows(NullPointerException.class, () -> snapshot("", Map.of(), new byte[0], Map.of(), null));
        Assertions.assertThrows(NullPointerException.class, () -> snapshot("", Map.of("x", java.util.Arrays.asList("a", null)),
            new byte[0], Map.of(), HttpReceiveState.COMPLETE));
        for (int index = 0; index < 4; index++) {
            HttpReceiveState[] states = {HttpReceiveState.COMPLETE, HttpReceiveState.COMPLETE,
                HttpReceiveState.COMPLETE, HttpReceiveState.COMPLETE};
            states[index] = null;
            Assertions.assertThrows(NullPointerException.class, () -> new HttpRequestSnapshot("", Map.of(), new byte[0],
                Map.of(), states[0], states[1], states[2], states[3]));
        }
    }

    @Test void publicSnapshotsOwnTheirInputsAndDoNotExposeMutableCollectionsOrBytes() {
        java.util.ArrayList<java.lang.String> values = new ArrayList<>(List.of("before"));
        java.util.LinkedHashMap<java.lang.String,java.util.List<java.lang.String>> headers = new java.util.LinkedHashMap<String, List<String>>();
        headers.put("x", values);
        byte[] body = new byte[]{1, 2, 3};
        com.inzent.apim.utilities.http.mockserver.HttpRequestSnapshot request = snapshot("POST / HTTP/1.1", headers, body, Map.of(), HttpReceiveState.COMPLETE);
        values.set(0, "after"); headers.clear(); body[0] = 9;
        Assertions.assertEquals(List.of("before"), request.headers().get("x"));
        Assertions.assertArrayEquals(new byte[]{1, 2, 3}, request.content());
        Assertions.assertThrows(UnsupportedOperationException.class, () -> request.headers().get("x").clear());
        request.content()[0] = 9;
        Assertions.assertArrayEquals(new byte[]{1, 2, 3}, request.content());
    }

    @Test void fragmentedMalformedInputDoesNotProduceInternalNpe() throws Exception {
        java.util.Random random = new java.util.Random(20261023L);
        List<String> prefixes = List.of("", "GET / HTTP/1.1\r\n", "POST / HTTP/1.1\r\nHost: local\r\nContent-Length: 16\r\n\r\n",
            "POST / HTTP/1.1\r\nHost: local\r\nTransfer-Encoding: chunked\r\n\r\n",
            "POST / HTTP/1.1\r\nHost: local\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n");
        for (String prefix : prefixes) {
            for (int trial = 0; trial < 400; trial++) {
                com.inzent.apim.utilities.http.mockserver.HttpRequestDecoder decoder = new HttpRequestDecoder(HttpProtocolLimits.defaults());
                decoder.feed(ByteBuffer.wrap(bytes(prefix)));
                byte[] input = new byte[32];
                random.nextBytes(input);
                try {
                    for (byte value : input) decoder.feed(ByteBuffer.wrap(new byte[]{value}));
                    Assertions.assertNotNull(decoder.endOfInput().request());
                } catch (java.io.IOException invalidInput) {
                    Assertions.assertThrows(IllegalStateException.class, () -> decoder.feed(ByteBuffer.allocate(0)));
                }
            }
        }
    }

    private static HttpRequestSnapshot snapshot(String initial, Map<String, List<String>> headers,
            byte[] content, Map<String, List<String>> trailers, HttpReceiveState state) {
        return new HttpRequestSnapshot(initial, headers, content, trailers, state, state, state, state);
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.US_ASCII); }
}
