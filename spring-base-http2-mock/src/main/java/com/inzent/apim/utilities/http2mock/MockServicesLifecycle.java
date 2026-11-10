package com.inzent.apim.utilities.http2mock;

import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import com.inzent.apim.utilities.http.mockserver.*;
import com.inzent.apim.utilities.netty.http.mockserver.server.HttpMockServer;

@Component
@EnableConfigurationProperties(MockServicesProperties.class)
public class MockServicesLifecycle implements SmartLifecycle {
    private final MockServicesProperties properties;
    private final ResourceLoader resources;
    private final List<Endpoint> endpoints = new CopyOnWriteArrayList<>();
    private HttpMockServer netty;
    private MockServerExecutor response;
    private volatile boolean running;
    private volatile String nettyState;
    private volatile String responseState;
    private volatile String nettyError;
    private volatile String responseError;
    private volatile List<Endpoint> nettyDetails = List.of();
    private volatile List<Endpoint> responseDetails;

    public MockServicesLifecycle(MockServicesProperties properties, ResourceLoader resources) {
        this.properties = properties; this.resources = resources;
        nettyState = properties.netty().enabled() ? "STOPPED" : "DISABLED";
        responseState = properties.response().enabled() ? "STOPPED" : "DISABLED";
        responseDetails = properties.response().bindings().stream()
                .map(b -> new Endpoint("response", b.name(), b.host(), b.port(), responseState)).toList();
    }
    public record Endpoint(String engine, String name, String host, int port, String state) { }
    public record EngineState(String engine, boolean enabled, String state, String error, List<Endpoint> endpoints) { }
    public List<Endpoint> endpoints() { return running ? List.copyOf(endpoints) : List.of(); }
    public List<EngineState> engineStates() {
        return List.of(snapshot("netty", properties.netty().enabled(), nettyState, nettyError, nettyDetails),
                snapshot("response", properties.response().enabled(), responseState, responseError, responseDetails));
    }
    private EngineState snapshot(String engine, boolean enabled, String state, String error, List<Endpoint> details) {
        return new EngineState(engine, enabled, state, error, details.stream()
                .map(e -> new Endpoint(e.engine(), e.name(), e.host(), e.port(), state)).toList());
    }

    public synchronized void control(String engine, String action) {
        if (!Set.of("netty", "response").contains(engine) || !Set.of("start", "stop").contains(action))
            throw new IllegalArgumentException("Unknown mock engine or operation");
        if (!running) throw new IllegalStateException("Application is stopping");
        if (!(engine.equals("netty") ? properties.netty().enabled() : properties.response().enabled()))
            throw new IllegalStateException("Mock engine is disabled by configuration");
        try {
            if (action.equals("start")) startEngine(engine); else stopEngine(engine);
        } catch (Exception failure) {
            System.getLogger(getClass().getName()).log(System.Logger.Level.ERROR, "Mock engine control failed: " + engine, failure);
            throw new IllegalStateException("Mock service operation failed; check ports and server configuration", failure);
        }
    }

    private void state(String engine, String state, String error) {
        if (engine.equals("netty")) { nettyError = error; nettyState = state; }
        else { responseError = error; responseState = state; }
    }
    private String failureType(Exception failure) {
        Throwable cause = failure;
        for (int depth = 0; depth < 8 && cause.getCause() != null; depth++) cause = cause.getCause();
        return cause.getClass().getSimpleName();
    }
    private void startEngine(String engine) throws Exception {
        if ((engine.equals("netty") ? nettyState : responseState).equals("RUNNING")) return;
        // A failed close must be retried before replacing its server reference.
        if (engine.equals("netty") ? netty != null : response != null) stopEngine(engine);
        state(engine, "STARTING", null);
        try {
            if (engine.equals("netty")) {
                var prepared = NettyMockFactory.create(properties.netty(), resources);
                netty = prepared.server();
                nettyDetails = List.of(new Endpoint("netty", "netty-http-mock", prepared.config().host(), prepared.config().port(), "STARTING"));
                int port = netty.start();
                endpoints.add(new Endpoint("netty", "netty-http-mock", prepared.config().host(), port, "RUNNING"));
                nettyDetails = endpoints.stream().filter(e -> e.engine().equals("netty")).toList();
            } else {
                startResponse();
                responseDetails = endpoints.stream().filter(e -> e.engine().equals("response")).toList();
            }
            state(engine, "RUNNING", null);
        } catch (Exception failure) {
            try { stopEngine(engine); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            state(engine, "ERROR", "Start failed: " + failureType(failure));
            throw failure;
        }
    }
    private void stopEngine(String engine) {
        state(engine, "STOPPING", null);
        try {
            if (engine.equals("netty")) {
                if (netty != null) { netty.close(); netty = null; }
            } else if (response != null) { response.close(); response = null; }
            boolean enabled = engine.equals("netty") ? properties.netty().enabled() : properties.response().enabled();
            state(engine, enabled ? "STOPPED" : "DISABLED", null);
        } catch (RuntimeException failure) {
            state(engine, "ERROR", "Stop failed: " + failureType(failure));
            throw failure;
        } finally { endpoints.removeIf(e -> e.engine().equals(engine)); }
    }

    @Override public synchronized void start() {
        if (running) return;
        try {
            if (properties.netty().enabled()) startEngine("netty");
            if (properties.response().enabled()) startEngine("response");
            running = true;
        } catch (Exception | Error failure) {
            try { closeServers(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw new IllegalStateException("Unable to start configured mock services", failure);
        }
    }
    private void startResponse() throws Exception {
        var settings = properties.response();
        if (settings.bindings().isEmpty()) throw new IllegalArgumentException("Response bindings must not be empty");
        response = new MockServerExecutor(new ServerOptions(settings.maxClients(), settings.shutdownTimeout()));
        Set<String> names = new HashSet<>();
        for (var binding : settings.bindings()) {
            if (binding.name() == null || binding.name().isBlank() || !names.add(binding.name()))
                throw new IllegalArgumentException("Response binding names must be nonempty and unique");
            validateDelay(binding.delay());
            var options = binding.options().toOptions();
            var scenario = scenario(binding);
            response.addMock(binding.name(), scenario, InetAddress.getByName(binding.host()), binding.port(), options, event -> {
                if (event.type() == ServiceEventType.SERVER_SOCKET_OPEN) endpoints.add(new Endpoint(
                        "response", binding.name(), event.localAddress().getAddress().getHostAddress(), event.localAddress().getPort(), "RUNNING"));
            });
        }
        response.start();
    }
    private static void validateDelay(Duration delay) {
        if (delay == null || delay.isNegative()) throw new IllegalArgumentException("Binding delay must be nonnegative");
        delay.toNanos();
    }
    private static MockScenario scenario(MockServicesProperties.Binding binding) {
        return switch (binding.scenario()) {
            case WAIT_FOR_CLIENT_DISCONNECT -> MockCase.WAIT_FOR_CLIENT_DISCONNECT;
            case SLOW_READ -> MockCase.SLOW_READ;
            case CLOSE -> context -> { throw new CloseClientException(binding.delay()); };
            case REPLY -> {
                var reply = binding.reply().toResponse();
                var limits = binding.reply().limits();
                yield context -> {
                    context.runPoint(MockPoint.ON_CONNECTED);
                    while (true) {
                        var request = context.readHttpProtocol(limits);
                        if (!request.completedRequests().isEmpty()) {
                            context.delay(binding.delay());
                            throw new SendResponseException(reply);
                        }
                        if (request.endOfInput()) return;
                    }
                };
            }
        };
    }
    private void closeServers() {
        RuntimeException failure = null;
        try { stopEngine("response"); } catch (RuntimeException error) { failure = error; }
        try { stopEngine("netty"); } catch (RuntimeException error) {
            if (failure == null) failure = error; else failure.addSuppressed(error);
        }
        running = false; endpoints.clear();
        if (failure != null) throw failure;
    }
    @Override public synchronized void stop() { closeServers(); }
    @PreDestroy public void destroy() { stop(); }
    @Override public void stop(Runnable callback) { try { stop(); } finally { callback.run(); } }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return 0; }
}
