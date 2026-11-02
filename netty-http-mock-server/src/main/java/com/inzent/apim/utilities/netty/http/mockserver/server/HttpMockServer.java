package com.inzent.apim.utilities.netty.http.mockserver.server;

import com.inzent.apim.utilities.netty.http.mockserver.config.ConfigLoader;
import com.inzent.apim.utilities.netty.http.mockserver.config.ServerConfig;
import com.inzent.apim.utilities.netty.http.mockserver.registry.ActionRegistry;
import java.util.Objects;

/**
 * Host-owned embedded HTTP mock server lifecycle.
 * Factories validate configuration/registration without binding a port or starting I/O threads.
 * No factory registers a JVM shutdown hook or terminates the host process.
 * Implementations own their event loops; call blocking lifecycle operations from a host thread.
 */
public interface HttpMockServer extends AutoCloseable {
    /** Loads YAML/arguments using the same rules as the executable server. Does not start the server. */
    static HttpMockServer fromArguments(String... arguments) {
        return create(ConfigLoader.load(Objects.requireNonNull(arguments,"arguments")));
    }

    /** Discovers actions under the configured packages. Does not start the server. */
    static HttpMockServer create(ServerConfig config) {
        return new NettyHttpMockServer(Objects.requireNonNull(config,"config"));
    }

    /** Uses a host-provided immutable registry instead of scanning the classpath. */
    static HttpMockServer create(ServerConfig config, ActionRegistry registry) {
        return new NettyHttpMockServer(Objects.requireNonNull(config,"config"),Objects.requireNonNull(registry,"registry"));
    }

    /**
     * Binds synchronously and returns the listening port; request handling proceeds on I/O threads.
     * Repeated calls while started return the same port. A closed/failed instance cannot restart.
     */
    int start();

    /**
     * Waits interruptibly for the listening channel to close; does not perform resource cleanup.
     * Call close() in a finally block or try-with-resources. Throws if not started.
     */
    void awaitClose() throws InterruptedException;

    /**
     * Closes the listener, connections, timers and owned event loops. Idempotent and blocking.
     * Must be called from outside this server's I/O event loops. No JVM shutdown or System.exit.
     */
    @Override void close();
}
