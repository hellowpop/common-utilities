package com.inzent.apim.utilities.netty.http.mockserver;

import com.inzent.apim.utilities.netty.http.mockserver.config.ConfigLoader;
import com.inzent.apim.utilities.netty.http.mockserver.registry.ActionRegistry;
import com.inzent.apim.utilities.netty.http.mockserver.server.HttpMockServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class EmbeddedServerTest {
    private static int freePort() throws Exception {
        try (ServerSocket socket=new ServerSocket(0)) { return socket.getLocalPort(); }
    }
    @Test void interfaceFromArgumentsStartsServesAndClosesWithoutOwningHostProcess() throws Exception {
        int port=freePort();
        try (HttpMockServer server=HttpMockServer.fromArguments("--server.port="+port,"--server.worker-threads=1")) {
            assertEquals(port,server.start()); assertEquals(port,server.start());
            try (Socket socket=new Socket("127.0.0.1",port)) {
                socket.setSoTimeout(2000);
                ServerIntegrationTest.send(socket,"GET / HTTP/1.1\r\nHost: localhost\r\n\r\n");
                assertEquals("OK",new String(ServerIntegrationTest.response(socket,false).body(),java.nio.charset.StandardCharsets.UTF_8));
            }
            server.close(); server.close();
            assertThrows(IllegalStateException.class,server::start);
        }
        try (ServerSocket rebound=new ServerSocket(port)) { assertEquals(port,rebound.getLocalPort()); }
    }
    @Test void explicitConfigAndRegistrySupportHostOwnedAwaitAndShutdown() throws Exception {
        int port=freePort();
        var config=ConfigLoader.load(new String[]{"--server.port="+port,"--server.worker-threads=1"});
        var registry=ActionRegistry.discover(List.of("com.inzent.apim.utilities.netty.http.mockserver.processor"));
        ExecutorService host=Executors.newSingleThreadExecutor();
        try (HttpMockServer server=HttpMockServer.create(config,registry)) {
            assertThrows(IllegalStateException.class,server::awaitClose);
            server.start();
            Future<?> waiting=host.submit(() -> {
                try { server.awaitClose(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new RuntimeException(ex); }
            });
            server.close(); waiting.get(3,TimeUnit.SECONDS);
        } finally { host.shutdownNow(); assertTrue(host.awaitTermination(3,TimeUnit.SECONDS)); }
    }
    @Test void configFactoryValidatesRegistryBeforeBinding() throws Exception {
        int port=freePort();
        var config=ConfigLoader.load(new String[]{"--server.port="+port,"--mock.default-action=missing"});
        assertThrows(IllegalArgumentException.class,() -> HttpMockServer.create(config));
        try (ServerSocket unbound=new ServerSocket(port)) { assertEquals(port,unbound.getLocalPort()); }
    }
}
