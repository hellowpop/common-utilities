package com.inzent.apim.utilities.http.mockserver;

import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NamedScenarioTest {
    @Test void registersCapturedConfigurationWithNameAndClosesItsListener() throws Exception {
        var port = new AtomicInteger();
        String body = "configured response";
        try (var server = new MockServerExecutor()) {
            server.addMock("configured-reply", context -> {
                throw new SendResponseException(MockResponse.utf8(201, "text/plain", body));
            }, InetAddress.getLoopbackAddress(), 0, MockOptions.defaults(), event -> {
                if (event.type() == ServiceEventType.SERVER_SOCKET_OPEN) {
                    assertEquals("configured-reply", event.mockCase());
                    port.set(event.localAddress().getPort());
                }
            });
            server.start();
            try (var socket = new Socket(InetAddress.getLoopbackAddress(), port.get())) {
                socket.setSoTimeout(3000);
                var response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(response.startsWith("HTTP/1.1 201"));
                assertTrue(response.endsWith(body));
            }
        }
        try (var released = new java.net.ServerSocket(port.get(), 1, InetAddress.getLoopbackAddress())) {
            assertEquals(port.get(), released.getLocalPort());
        }
    }
}
