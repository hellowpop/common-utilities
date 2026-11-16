package com.inzent.apim.utilities.http.mockserver;

import java.io.IOException;
import java.net.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

final class MockBinding {
    final long id;
    final MockScenario scenario;
    final String name;
    final InetSocketAddress address;
    final MockOptions options;
    final ServiceStatusListenerChain listeners;
    final AtomicBoolean closedEvent = new AtomicBoolean();
    volatile ServerSocket socket;
    volatile Thread acceptThread;
    volatile boolean failed;
    boolean started;
    boolean opened;
    Map<String, Object> socketOptions = Map.of();

    MockBinding(long id, MockScenario scenario, String name, InetSocketAddress address,
                MockOptions options, ServiceStatusListener listener) {
        this.id = id; this.scenario = scenario; this.name = name; this.address = address;
        this.options = options; this.listeners = ServiceStatusListenerChain.of(listener);
    }
    void open() throws IOException {
        socket = new ServerSocket();
        socket.setReuseAddress(options.reuseAddress());
        if (options.serverReceiveBufferSize() != null) socket.setReceiveBufferSize(options.serverReceiveBufferSize());
        socket.bind(address, options.backlog());
        opened = true;
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("requested", options);
        values.put("reuseAddress", socket.getReuseAddress());
        values.put("receiveBufferSize", socket.getReceiveBufferSize());
        socketOptions = Map.copyOf(values);
        event(ServiceEventType.SERVER_SOCKET_OPEN, null);
    }
    InetSocketAddress localAddress() {
        ServerSocket s = socket;
        return s != null && s.isBound() ? (InetSocketAddress) s.getLocalSocketAddress() : address;
    }
    void emit(ServiceEvent event) {
        ExecutionGuard.enter();
        try { listeners.onEvent(event); }
        finally { ExecutionGuard.exit(); }
    }
    void event(ServiceEventType type, Throwable failure) {
        emit(new ServiceEvent(Instant.now(), id, name, null, type, localAddress(), null,
            null, 0, 0, null, failure, false, null, socketOptions));
    }
    void closeSocket() throws IOException { if (socket != null) socket.close(); }
    void closedEvent() {
        if (opened && closedEvent.compareAndSet(false, true)) event(ServiceEventType.SERVER_SOCKET_CLOSED, null);
    }
}
