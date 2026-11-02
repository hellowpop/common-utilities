package com.inzent.apim.utilities.netty.http.mockserver;

import com.inzent.apim.utilities.netty.http.mockserver.config.ConfigLoader;
import com.inzent.apim.utilities.netty.http.mockserver.server.NettyHttpMockServer;

public final class MockServerApplication {
    private MockServerApplication() {}
    public static void main(String[] args) {
        try (NettyHttpMockServer server=new NettyHttpMockServer(ConfigLoader.load(args))) {
            Thread hook=new Thread(server::close,"mock-server-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            try {
                int port=server.start();
                System.out.println("HTTP mock server listening on port " + port);
                server.awaitClose();
            } finally {
                try { Runtime.getRuntime().removeShutdownHook(hook); }
                catch (IllegalStateException ignored) { /* JVM shutdown already in progress. */ }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            String detail=ex instanceof IllegalArgumentException ? ": " + ex.getMessage() : "";
            System.err.println("HTTP mock server startup/runtime failure: " + ex.getClass().getSimpleName() + detail);
            System.exit(1);
        }
    }
}
