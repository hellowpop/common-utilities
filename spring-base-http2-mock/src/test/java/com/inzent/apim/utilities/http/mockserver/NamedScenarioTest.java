package com.inzent.apim.utilities.http.mockserver;

import org.junit.jupiter.api.Assertions;

import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;


class NamedScenarioTest {
    @Test void registersCapturedConfigurationWithNameAndClosesItsListener() throws Exception {
        java.util.concurrent.atomic.AtomicInteger port = new AtomicInteger();
        String body = "configured response";
        try (com.inzent.apim.utilities.http.mockserver.MockServerExecutor server = new MockServerExecutor()) {
            server.addMock("configured-reply", context -> {
                throw new SendResponseException(MockResponse.utf8(201, "text/plain", body));
            }, InetAddress.getLoopbackAddress(), 0, MockOptions.defaults(), event -> {
                if (event.type() == ServiceEventType.SERVER_SOCKET_OPEN) {
                    Assertions.assertEquals("configured-reply", event.mockCase());
                    port.set(event.localAddress().getPort());
                }
            });
            server.start();
            try (java.net.Socket socket = new Socket(InetAddress.getLoopbackAddress(), port.get())) {
                socket.setSoTimeout(3000);
                java.lang.String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                Assertions.assertTrue(response.startsWith("HTTP/1.1 201"));
                Assertions.assertTrue(response.endsWith(body));
            }
        }
        try (java.net.ServerSocket released = new java.net.ServerSocket(port.get(), 1, InetAddress.getLoopbackAddress())) {
            Assertions.assertEquals(port.get(), released.getLocalPort());
        }
    }
}
